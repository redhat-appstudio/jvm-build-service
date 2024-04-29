package com.redhat.hacbs.container.deploy.mavenrepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

import com.amazonaws.services.codeartifact.model.DeletePackageVersionsRequest;
import com.amazonaws.services.codeartifact.model.PackageFormat;
import com.amazonaws.services.codeartifact.model.ResourceNotFoundException;
import com.amazonaws.services.codeartifact.model.ThrottlingException;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.logging.Log;

public class MavenRepositoryDeployer {
    private final String username;

    private final String password;

    private final String repository;

    private final Path artifacts;

    private final RepositorySystem system;

    private final DefaultRepositorySystemSession session;

    private final CodeArtifactRepository codeArtifactRepository;

    public MavenRepositoryDeployer(BootstrapMavenContext mvnCtx, String username, String password, String repository,
            Path artifacts, CodeArtifactRepository codeArtifactRepository)
            throws BootstrapMavenException {
        this.username = username;
        this.password = password;
        this.repository = repository;
        this.artifacts = artifacts;

        this.system = mvnCtx.getRepositorySystem();
        this.codeArtifactRepository = codeArtifactRepository;
        this.session = MavenRepositorySystemUtils.newSession();

        Log.infof("Maven credentials are username '%s' and repository '%s'", username, repository);

        // https://maven.apache.org/resolver/third-party-integrations.html states a local repository manager should be added.
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, new LocalRepository(artifacts.toFile())));
    }

    public void deploy()
            throws IOException {
        RemoteRepository distRepo = new RemoteRepository.Builder("repo",
                "default",
                repository)
                .setAuthentication(new AuthenticationBuilder().addUsername(username)
                        .addPassword(password).build())
                .build();

        Files.walkFileTree(artifacts,
                new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        try (var stream = Files.list(dir)) {
                            List<Path> files = stream.sorted().toList();
                            boolean hasPom = files.stream().anyMatch(s -> s.toString().endsWith(".pom"));
                            if (hasPom) {

                                Path relative = artifacts.relativize(dir);
                                String group = relative.getParent().getParent().toString().replace(File.separatorChar,
                                        '.');
                                String artifact = relative.getParent().getFileName().toString();
                                String version = dir.getFileName().toString();
                                Log.info(
                                        "GROUP: " + group + " , ARTIFACT:" + artifact + " , VERSION: "
                                                + version);
                                Pattern p = Pattern
                                        .compile(artifact + "-" + version + "(-(\\w+))?\\.(\\w+)");

                                DeployRequest deployRequest = new DeployRequest();
                                deployRequest.setRepository(distRepo);
                                for (var i : files) {
                                    Matcher matcher = p.matcher(i.getFileName().toString());
                                    if (matcher.matches()) {
                                        Artifact jarArtifact = new DefaultArtifact(group, artifact,
                                                matcher.group(2),
                                                matcher.group(3),
                                                version);
                                        jarArtifact = jarArtifact.setFile(i.toFile());
                                        deployRequest.addArtifact(jarArtifact);

                                        if (codeArtifactRepository != null) {
                                            handleThrottling(() -> {
                                                try {
                                                    DeletePackageVersionsRequest request = new DeletePackageVersionsRequest()
                                                            .withPackage(artifact)
                                                            .withRepository(codeArtifactRepository.repository())
                                                            .withDomain(codeArtifactRepository.domain())
                                                            .withFormat(PackageFormat.Maven)
                                                            .withNamespace(group)
                                                            .withVersions(version);
                                                    var result = codeArtifactRepository.client().deletePackageVersions(request);
                                                    Log.infof("Deleted packages %s", result);
                                                } catch (ResourceNotFoundException e) {
                                                    //not found
                                                }
                                            });
                                        }
                                    }
                                }

                                try {
                                    Log.infof("Deploying %s", deployRequest);
                                    system.deploy(session, deployRequest);
                                } catch (DeploymentException e) {
                                    throw new RuntimeException(e);
                                }
                            } else {
                                if (files.stream().anyMatch(p -> !p.toFile().isDirectory())) {
                                    Log.warnf("For directory %s, no pom file found with files %s", dir,
                                            files);
                                }
                            }

                            return FileVisitResult.CONTINUE;
                        }
                    }

                });
    }

    private void handleThrottling(Runnable task) {
        for (int i = 0; i < 10; ++i) {
            try {
                task.run();
                return;
            } catch (RuntimeException e) {
                if (!isThrottle(e)) {
                    throw e;
                }
            }
            try {
                Thread.sleep(i * 10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private boolean isThrottle(RuntimeException e) {
        Throwable ex = e;
        while (ex != null) {
            if (ex instanceof ThrottlingException) {
                return true;
            }
            ex = ex.getCause();
        }
        return false;
    }

}

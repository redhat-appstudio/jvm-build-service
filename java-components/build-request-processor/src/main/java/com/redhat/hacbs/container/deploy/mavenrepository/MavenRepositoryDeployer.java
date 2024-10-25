package com.redhat.hacbs.container.deploy.mavenrepository;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;

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

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.logging.Log;

public class MavenRepositoryDeployer {
    private final String username;

    private final String password;

    private final String repository;

    private final Path artifacts;

    private final RepositorySystem system;

    private final RepositorySystemSession session;

    private final String serverId;

    public MavenRepositoryDeployer(BootstrapMavenContext mvnCtx, String username, String password, String repository, String serverId, Path artifacts)
            throws BootstrapMavenException {
        this.username = username;
        this.password = password;
        this.repository = repository;
        this.artifacts = artifacts;
        this.serverId = serverId;

        this.system = mvnCtx.getRepositorySystem();
        // Note - we were using MavenRepositorySystemUtils.newSession but that doesn't correctly process
        //  the settings.xml without an active project.
        this.session = mvnCtx.getRepositorySystemSession();
    }

    public void deploy()
            throws IOException {
        RemoteRepository result;
        RemoteRepository initial = new RemoteRepository.Builder(serverId,
                "default",
                repository).build();
        RemoteRepository.Builder builder = new RemoteRepository.Builder(initial);

        if (isNotEmpty(username)) {
            builder.setAuthentication(new AuthenticationBuilder().addUsername(username)
                .addPassword(password).build());
        } else {
            builder.setAuthentication(session.getAuthenticationSelector().getAuthentication(initial));
        }
        if (initial.getProxy() == null) {
            builder.setProxy(session.getProxySelector().getProxy(initial));
        }
        result = builder.build();

        Log.infof("Configured repository %s", result);

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
                                if (relative.getNameCount() <= 2) {
                                    Log.errorf("Invalid repository format. Local directory is '%s' with relative path '%s' and not enough components to calculate groupId and artifactId", artifacts, relative);
                                }
                                // If we're in org/foobar/artifact/1.0 then the group is two up and the artifact is one up.
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
                                deployRequest.setRepository(result);
                                for (var i : files) {
                                    Matcher matcher = p.matcher(i.getFileName().toString());
                                    if (matcher.matches()) {
                                        Artifact jarArtifact = new DefaultArtifact(group, artifact,
                                                matcher.group(2),
                                                matcher.group(3),
                                                version);
                                        Log.infof("Uploading %s", jarArtifact);
                                        jarArtifact = jarArtifact.setFile(i.toFile());
                                        deployRequest.addArtifact(jarArtifact);
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
}

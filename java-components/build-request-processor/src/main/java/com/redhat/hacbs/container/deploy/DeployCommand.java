package com.redhat.hacbs.container.deploy;

import static org.apache.commons.lang3.ObjectUtils.isNotEmpty;

import java.nio.file.Path;
import java.util.Optional;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.container.deploy.mavenrepository.MavenRepositoryDeployer;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "deploy")
public class DeployCommand implements Runnable {

    @CommandLine.Option(names = "--directory")
    String artifactDirectory;

    // Maven Repo Deployment specification
    @CommandLine.Option(names = "--mvn-username")
    String mvnUser;

    @ConfigProperty(name = "maven.password")
    Optional<String> mvnPassword;

    @CommandLine.Option(names = "--mvn-settings")
    String mvnSettings;

    @CommandLine.Option(names = "--mvn-repo")
    String mvnRepo;

    @CommandLine.Option(names = "--server-id")
    String serverId = "indy-mvn";

    @Inject
    BootstrapMavenContext mvnCtx;

    public void run() {
        try {
            var deploymentPath = Path.of(artifactDirectory);

            if (!deploymentPath.toFile().exists()) {
                Log.warnf("No deployed artifacts found. Has the build been correctly configured to deploy?");
                throw new RuntimeException("Deploy failed");
            }
            if (isNotEmpty(mvnSettings)) {
                System.setProperty("maven.settings", mvnSettings);
            }
            if (isNotEmpty(mvnRepo)) {
                // Maven Repo Deployment
                MavenRepositoryDeployer deployer = new MavenRepositoryDeployer(mvnCtx, mvnUser, mvnPassword.orElse(""), mvnRepo, serverId, deploymentPath);
                deployer.deploy();
            }

        } catch (Exception e) {
            Log.error("Deployment failed", e);
            throw new RuntimeException(e);
        }
    }
}

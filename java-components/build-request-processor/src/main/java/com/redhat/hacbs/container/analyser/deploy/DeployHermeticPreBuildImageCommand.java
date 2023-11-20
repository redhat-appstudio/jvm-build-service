package com.redhat.hacbs.container.analyser.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.container.analyser.deploy.containerregistry.ContainerRegistryDeployer;

import picocli.CommandLine;

@CommandLine.Command(name = "deploy-hermetic-pre-build-image", description = "This adds an additional layer onto an existing pre-build-image that"
        +
        "contains a list of all dependencies needed by the build. This can be used to generate a hermetic build.")
public class DeployHermeticPreBuildImageCommand implements Runnable {
    @CommandLine.Option(names = "--source-image", required = true)
    String builderImage;
    @CommandLine.Option(names = "--image-name")
    String imageName;

    @CommandLine.Option(names = "--image-hash", required = true)
    Path imageHash;
    @CommandLine.Option(names = "--build-artifact-path", required = true)
    Path buildArtifactsPath;

    @CommandLine.Option(names = "--repository-path", required = true)
    Path repositoryPath;
    @CommandLine.Option(names = "--image-source-path", required = true)
    String imageSourcePath;

    @CommandLine.Option(names = "--registry-host", defaultValue = "quay.io")
    String host;
    @CommandLine.Option(names = "--registry-port", defaultValue = "443")
    int port;
    @CommandLine.Option(names = "--registry-owner", defaultValue = "hacbs")
    String owner;
    @ConfigProperty(name = "registry.token")
    Optional<String> token;
    @CommandLine.Option(names = "--registry-repository", defaultValue = "artifact-deployments")
    String repository;
    @CommandLine.Option(names = "--registry-insecure", defaultValue = "false")
    boolean insecure;
    @CommandLine.Option(names = "--registry-prepend-tag", defaultValue = "")
    String prependTag;

    public void run() {
        ContainerRegistryDeployer deployer = new ContainerRegistryDeployer(host, port, owner, token.orElse(""), repository,
                insecure,
                prependTag);
        try {
            deployer.deployHermeticPreBuildImage(builderImage, buildArtifactsPath, repositoryPath, imageSourcePath, imageName,
                    new BiConsumer<String, String>() {
                        @Override
                        public void accept(String name, String hash) {
                            try {
                                Files.writeString(imageHash, name.substring(0, name.lastIndexOf(":")) + "@sha256:" + hash);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}

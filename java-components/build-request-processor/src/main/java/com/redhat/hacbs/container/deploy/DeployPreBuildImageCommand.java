package com.redhat.hacbs.container.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.container.deploy.containerregistry.ContainerRegistryDeployer;

import picocli.CommandLine;

@Deprecated
@CommandLine.Command(name = "deploy-pre-build-image", description = "This command will deploy a builder image containing the checked out source to an image registry."
        +
        "This image is used in a later step to actually build the artifact.")
public class DeployPreBuildImageCommand implements Runnable {

    @CommandLine.Option(names = "--source-path", required = true)
    Path sourcePath;
    @CommandLine.Option(names = "--image-hash", required = true)
    Path imageHash;

    @CommandLine.Option(names = "--image-source-path", required = true)
    String imageSourcePath;

    @CommandLine.Option(names = "--image-name")
    String imageName;

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
            DeployCommand.cleanBrokenSymlinks(sourcePath);
            deployer.deployPreBuildImage(sourcePath, imageSourcePath, imageName,
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

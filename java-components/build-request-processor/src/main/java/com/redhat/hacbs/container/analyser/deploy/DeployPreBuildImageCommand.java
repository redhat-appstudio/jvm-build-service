package com.redhat.hacbs.container.analyser.deploy;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.container.analyser.deploy.containerregistry.ContainerRegistryDeployer;

import picocli.CommandLine;

@CommandLine.Command(name = "deploy-pre-build-image")
public class DeployPreBuildImageCommand implements Runnable {

    @CommandLine.Option(names = "--builder-image", required = true)
    String builderImage;
    @CommandLine.Option(names = "--source-path", required = true)
    Path sourcePath;

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
                prependTag, new BiConsumer<String, String>() {
                    @Override
                    public void accept(String s, String s2) {

                    }
                }, "");
        try {
            deployer.deployPreBuildImage(builderImage, sourcePath, imageSourcePath, imageName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}

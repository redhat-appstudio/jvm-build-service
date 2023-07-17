package com.redhat.hacbs.container.analyser.deploy;

import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.container.analyser.deploy.containerregistry.ContainerRegistryDeployer;

import picocli.CommandLine;

@CommandLine.Command(name = "tag-container")
public class ContainerTagCommand implements Runnable {

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

    @CommandLine.Option(names = "--image-id", required = true)
    String imageId;

    @CommandLine.Parameters(split = ",")
    List<String> gavs;

    @Override
    public void run() {
        if (gavs == null || gavs.isEmpty()) {
            return; //nothing was deployed (e.g. contaminated build)
        }

        ContainerRegistryDeployer deployer = new ContainerRegistryDeployer(host, port, owner, token.orElse(""), repository,
                insecure,
                prependTag, imageId);
        try {
            deployer.tagArchive(gavs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

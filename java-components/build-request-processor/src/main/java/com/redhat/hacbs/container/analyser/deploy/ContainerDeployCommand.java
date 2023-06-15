package com.redhat.hacbs.container.analyser.deploy;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.container.analyser.deploy.containerregistry.ContainerRegistryDeployer;
import com.redhat.hacbs.container.results.ResultsUpdater;

import picocli.CommandLine;

@CommandLine.Command(name = "deploy-container")
public class ContainerDeployCommand extends DeployCommand {

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

    @Inject
    public ContainerDeployCommand(BeanManager beanManager, ResultsUpdater resultsUpdater) {
        super(beanManager, resultsUpdater);
    }

    @Override
    protected void doDeployment(Path deployDir, Path sourcePath, Path logsPath, Set<String> gavs) throws Exception {
        ContainerRegistryDeployer deployer = new ContainerRegistryDeployer(host, port, owner, token.orElse(""), repository,
                insecure,
                prependTag, new BiConsumer<String, String>() {
                    @Override
                    public void accept(String s, String hash) {
                        imageName = s;
                        imageDigest = hash;
                    }
                });
        deployer.deployArchive(deployDir, sourcePath, logsPath, gavs);

    }
}

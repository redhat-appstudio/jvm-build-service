package com.redhat.hacbs.container.analyser.deploy;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.container.analyser.deploy.containerregistry.ContainerRegistryDeployer;

import io.fabric8.kubernetes.client.KubernetesClient;
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
    public ContainerDeployCommand(BeanManager beanManager, KubernetesClient kubernetesClient) {
        super(beanManager, kubernetesClient);
    }

    @Override
    protected void doDeployment(Path deployDir, Path sourcePath, Path logsPath, Set<String> gavs) throws Exception {
        ContainerRegistryDeployer deployer = new ContainerRegistryDeployer(host, port, owner, token.orElse(""), repository,
                insecure,
                prependTag, new Consumer<String>() {
                    @Override
                    public void accept(String s) {
                        imageName = s;
                    }
                });
        deployer.deployArchive(deployDir, sourcePath, logsPath, gavs);

    }
}

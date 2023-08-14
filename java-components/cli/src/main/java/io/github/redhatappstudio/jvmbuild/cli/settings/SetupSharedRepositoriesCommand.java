package io.github.redhatappstudio.jvmbuild.cli.settings;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.redhat.hacbs.resources.model.v1alpha1.JBSConfig;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;
import com.redhat.hacbs.resources.model.v1alpha1.jbsconfigspec.SharedRegistries;

import io.fabric8.kubernetes.client.KubernetesClient;
import picocli.CommandLine;
import picocli.CommandLine.*;

@CommandLine.Command(name = "shared-repos", mixinStandardHelpOptions = true, description = "Setup shared repositories")
public class SetupSharedRepositoriesCommand {
    @Inject
    KubernetesClient client;

    @Command(mixinStandardHelpOptions = true, description = "List shared repositories")
    public void list() {
        var resource = client.resources(JBSConfig.class).withName(ModelConstants.JBS_CONFIG_NAME);
        JBSConfig config = resource.get();
        if (config != null) {
            var registries = config.getSpec().getSharedRegistries();
            if (registries != null) {
                for (var r : registries) {
                    System.out.println(
                            "Found shared image registry of " + r.getHost() + ',' + r.getPort() + ',' + r.getOwner() + ','
                                    + r.getRepository()
                                    + ',' + r.getInsecure() + ',' + r.getPrependTag());
                }
            }

        }
    }

    @Command(mixinStandardHelpOptions = true, description = "Add a shared repository")
    public void add(@CommandLine.ArgGroup(exclusive = false) Group group) {
        var resource = client.resources(JBSConfig.class).withName(ModelConstants.JBS_CONFIG_NAME);
        JBSConfig config = resource.get();
        if (config != null) {
            var registries = config.getSpec().getSharedRegistries();
            if (StringUtils.isAnyBlank(group.host, group.owner, group.repository)) {
                throw new RuntimeException("Host, owner and repository must be defined for a shared repository.");
            }
            SharedRegistries ir = group.getImageRegistry();
            System.out.println("Creating shared repo for " + group.repository);
            if (registries == null) {
                registries = new ArrayList<>();
            }
            registries.add(ir);
            config.getSpec().setSharedRegistries(registries);
            resource.patch(config);
        }
    }

    @Command(mixinStandardHelpOptions = true, description = "Delete a shared repository")
    public void delete(@CommandLine.ArgGroup(exclusive = false) Group group) {
        var resource = client.resources(JBSConfig.class).withName(ModelConstants.JBS_CONFIG_NAME);
        JBSConfig config = resource.get();
        if (config != null) {
            var registries = config.getSpec().getSharedRegistries();
            if (registries != null) {
                SharedRegistries ir = group.getImageRegistry();
                Optional<SharedRegistries> found = registries.stream().filter(r -> Objects.equals(ir.getHost(), r.getHost()) &&
                        Objects.equals(ir.getPort(), r.getPort()) &&
                        Objects.equals(ir.getOwner(), r.getOwner()) &&
                        Objects.equals(ir.getRepository(), r.getRepository()) &&
                        Objects.equals(ir.getInsecure(), r.getInsecure()) &&
                        Objects.equals(ir.getPrependTag(), r.getPrependTag())).findFirst();
                if (found.isPresent()) {
                    System.out.println("Removing shared repo " + group.repository);
                    registries.remove(found.get());
                    config.getSpec().setSharedRegistries(registries);
                    resource.patch(config);
                }
            }
        }
    }

    /**
     * ImageRegistry definition from jbsconfig_types.go:
     * <br/>
     * type ImageRegistry struct {
     * Host string `json:"host,omitempty"` // Defaults to quay.io in handleNoImageSecretFound
     * Port string `json:"port,omitempty"`
     * Owner string `json:"owner,omitempty"`
     * Repository string `json:"repository,omitempty"` // Defaults to artifact-deployments in handleNoImageSecretFound
     * Insecure bool `json:"insecure,omitempty"`
     * PrependTag string `json:"prependTag,omitempty"`
     * }
     */
    static class Group {
        @CommandLine.Option(names = "--host")
        String host;

        @CommandLine.Option(names = "--port")
        String port;

        @CommandLine.Option(names = "--owner")
        String owner;

        @CommandLine.Option(names = "--repository")
        String repository;

        @CommandLine.Option(names = "--insecure")
        boolean insecure;

        @CommandLine.Option(names = "--prependTag")
        String prependTag;

        SharedRegistries getImageRegistry() {
            SharedRegistries ir = new SharedRegistries();
            ir.setHost(host);
            ir.setPort(port);
            ir.setOwner(owner);
            ir.setRepository(repository);
            ir.setInsecure(insecure);
            ir.setPrependTag(prependTag);
            return ir;
        }
    }
}

package io.github.redhatappstudio.jvmbuild.cli.settings;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;

import com.redhat.hacbs.resources.model.v1alpha1.JBSConfig;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.client.KubernetesClient;
import picocli.CommandLine;

@CommandLine.Command(name = "rebuilds", mixinStandardHelpOptions = true, description = "Setup the JVM Build Service")
public class SetupRebuildsCommand implements Runnable {

    @Inject
    KubernetesClient client;

    @Override
    public void run() {

        var resource = client.resources(JBSConfig.class).withName(ModelConstants.JBS_CONFIG_NAME);
        JBSConfig config = resource.get();
        if (config != null) {
            if (Boolean.TRUE.equals(config.getSpec().getEnableRebuilds())) {
                System.out.println("Rebuilds are already enabled for this namespace");
                return;
            }
            config.getSpec().setEnableRebuilds(true);
            config.getSpec().setRequireArtifactVerification(true);
            resource.patch(config);
        } else {
            config = new JBSConfig();
            config.getMetadata().setName(ModelConstants.JBS_CONFIG_NAME);
            config.getSpec().setEnableRebuilds(true);
            config.getSpec().setRequireArtifactVerification(true);
            client.resource(config).create();
        }
        CompletableFuture<Object> latch = new CompletableFuture<Object>();
        long timeout = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < timeout) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            var obj = client.resources(JBSConfig.class).withName(ModelConstants.JBS_CONFIG_NAME).get();
            System.out.println(Objects.toString(obj.getStatus().getMessage(), "Working..."));
            if (Boolean.TRUE.equals(obj.getStatus().getRebuildsPossible())) {
                System.out.println("Rebuilds setup successfully");
                return;
            }
        }

    }

}

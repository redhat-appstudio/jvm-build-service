package io.github.redhatappstudio.jvmbuild.cli.settings;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.inject.Inject;

import com.redhat.hacbs.resources.model.v1alpha1.JBSConfig;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "rebuilds", mixinStandardHelpOptions = true, description = "Setup the JVM Build Service")
public class SetupRebuildsCommand implements Runnable {

    @Inject
    KubernetesClient client;

    @Override
    public void run() {

        var resource = client.resources(JBSConfig.class).withName(JBSConfig.NAME);
        JBSConfig config = resource.get();
        if (config != null) {
            if (config.getSpec().isEnableRebuilds()) {
                System.out.println("Rebuilds are already enabled for this namespace");
                return;
            }
            config.getSpec().setEnableRebuilds(true);
            config.getSpec().setRequireArtifactVerification(true);
            resource.patch(config);
        } else {
            config = new JBSConfig();
            config.getMetadata().setName(JBSConfig.NAME);
            config.getSpec().setEnableRebuilds(true);
            config.getSpec().setRequireArtifactVerification(true);
            client.resource(config).create();
        }
        CompletableFuture<Object> latch = new CompletableFuture<Object>();
        try (var watch = client.resources(JBSConfig.class).inNamespace(client.getNamespace()).inform()) {
            watch.addEventHandler(new ResourceEventHandler<JBSConfig>() {
                @Override
                public void onAdd(JBSConfig obj) {
                    System.out.println(Objects.toString(obj.getStatus().getMessage(), "Working..."));
                    if (obj.getStatus().isRebuildsPossible()) {
                        latch.complete(null);
                    }
                }

                @Override
                public void onUpdate(JBSConfig oldObj, JBSConfig newObj) {
                    System.out.println(Objects.toString(newObj.getStatus().getMessage(), "Working..."));
                    if (newObj.getStatus().isRebuildsPossible()) {
                        latch.complete(null);
                    }
                }

                @Override
                public void onDelete(JBSConfig obj, boolean deletedFinalStateUnknown) {

                }
            });
            try {
                latch.get(3, TimeUnit.SECONDS);
                System.out.println("Rebuilds setup successfully");
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            } catch (TimeoutException e) {
                Log.errorf("Timed out setting up rebuilds, check for any error message in the logs above.");
            }
        }

    }

}

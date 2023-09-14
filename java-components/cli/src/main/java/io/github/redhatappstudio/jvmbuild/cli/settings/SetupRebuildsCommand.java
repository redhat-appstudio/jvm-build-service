package io.github.redhatappstudio.jvmbuild.cli.settings;

import jakarta.inject.Inject;

import com.redhat.hacbs.resources.model.v1alpha1.JBSConfig;
import com.redhat.hacbs.resources.model.v1alpha1.JBSConfigSpec;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.client.KubernetesClient;
import picocli.CommandLine;

@CommandLine.Command(name = "rebuilds", mixinStandardHelpOptions = true, description = "Setup the JVM Build Service")
public class SetupRebuildsCommand implements Runnable {

    @Inject
    KubernetesClient client;

    @CommandLine.Option(names = "--private-repo")
    boolean privateRepo;

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
            config.getSpec().getRegistry().set_private(privateRepo);
            resource.patch(config);
        } else {
            config = new JBSConfig();
            config.setSpec(new JBSConfigSpec());
            config.getMetadata().setName(ModelConstants.JBS_CONFIG_NAME);
            config.getSpec().setEnableRebuilds(true);
            config.getSpec().setRequireArtifactVerification(true);
            config.getSpec().getRegistry().set_private(privateRepo);
            client.resource(config).create();
        }
        long timeout = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < timeout) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            var obj = client.resources(JBSConfig.class).withName(ModelConstants.JBS_CONFIG_NAME).get();
            if (obj.getStatus() == null || obj.getStatus().getMessage() == null) {
                System.out.println("Working...");
            } else {
                System.out.println(obj.getStatus().getMessage());
            }
            if (Boolean.TRUE.equals(obj.getStatus().getRebuildsPossible())) {
                System.out.println("Rebuilds setup successfully");
                return;
            }
        }

    }

}

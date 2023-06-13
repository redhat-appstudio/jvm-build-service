package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import jakarta.inject.Inject;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.util.ResourceNameUtils;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "create", mixinStandardHelpOptions = true, description = "Creates an artifact build for a specified GAV.")
public class ArtifactCreateCommand implements Runnable {

    @Inject
    KubernetesClient client;

    @CommandLine.Option(names = "-g", description = "The GAV to build", required = true)
    String targetGav;

    @Override
    public void run() {
        var client = Arc.container().instance(KubernetesClient.class).get();
        ArtifactBuild artifactBuild = new ArtifactBuild();
        artifactBuild.getMetadata().setName(ResourceNameUtils.nameFromGav(targetGav));
        artifactBuild.getSpec().setGav(targetGav);
        client.resource(artifactBuild).create();
    }

}

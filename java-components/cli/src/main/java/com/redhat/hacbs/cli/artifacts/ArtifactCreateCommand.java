package com.redhat.hacbs.cli.artifacts;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildSpec;
import com.redhat.hacbs.resources.util.ResourceNameUtils;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "create", mixinStandardHelpOptions = true, description = "Creates an artifact build for a specified GAV.")
public class ArtifactCreateCommand implements Runnable {

    @CommandLine.Option(names = "-g", description = "The GAV to build", required = true)
    String targetGav;

    @Override
    public void run() {
        var client = Arc.container().instance(KubernetesClient.class).get();
        ArtifactBuild artifactBuild = new ArtifactBuild();
        artifactBuild.setSpec(new ArtifactBuildSpec());
        artifactBuild.getMetadata().setName(ResourceNameUtils.nameFromGav(targetGav));
        artifactBuild.getSpec().setGav(targetGav);
        client.resource(artifactBuild).create();
    }

}

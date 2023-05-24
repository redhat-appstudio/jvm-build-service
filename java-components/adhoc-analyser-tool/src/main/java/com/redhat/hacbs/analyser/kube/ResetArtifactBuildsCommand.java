package com.redhat.hacbs.analyser.kube;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "reset-artifact-builds")
public class ResetArtifactBuildsCommand implements Runnable {

    @Inject
    KubernetesClient kubernetesClient;

    @CommandLine.Option(names = "-b", defaultValue = "")
    String build;

    @CommandLine.Option(names = "-m", defaultValue = "false")
    boolean missing;

    @Override
    public void run() {
        try {
            MixedOperation<ArtifactBuild, KubernetesResourceList<ArtifactBuild>, Resource<ArtifactBuild>> client = kubernetesClient
                    .resources(ArtifactBuild.class);
            if (!build.isEmpty()) {
                ArtifactBuild request = client.withName(build).get();
                request.getMetadata().setAnnotations(Map.of("jvmbuildservice.io/rebuild", "true"));
                client.createOrReplace(request);
            } else {
                List<ArtifactBuild> items = client.list().getItems();
                for (var request : items) {
                    if (!missing || request.getStatus().getState().equals("ArtifactBuildMissing")) {
                        request.getMetadata().setAnnotations(Map.of("jvmbuildservice.io/rebuild", "true"));
                        client.createOrReplace(request);
                    }
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build requests");
        }
    }
}

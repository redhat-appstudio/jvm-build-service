package com.redhat.hacbs.analyser.kube;

import java.util.List;

import javax.inject.Inject;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildRequest;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "reset-artifact-build-requests")
public class ResetArtifactBuildRequestsCommand implements Runnable {

    @Inject
    KubernetesClient kubernetesClient;

    @CommandLine.Option(names = "-b", defaultValue = "")
    String build;

    @Override
    public void run() {
        try {
            MixedOperation<ArtifactBuildRequest, KubernetesResourceList<ArtifactBuildRequest>, Resource<ArtifactBuildRequest>> client = kubernetesClient
                    .resources(ArtifactBuildRequest.class);
            if (!build.isEmpty()) {
                ArtifactBuildRequest item = client.withName(build).get();
                item.getStatus().setState("");
                client.updateStatus(item);
            } else {
                List<ArtifactBuildRequest> items = client.list().getItems();
                for (var request : items) {
                    request.getStatus().setState("");
                    client.updateStatus(request);
                }
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build requests");
        }
    }
}

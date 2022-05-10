package com.redhat.hacbs.analyser.kube;

import java.util.List;

import javax.inject.Inject;

import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildRequest;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildRequestStatus;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "list-build-requests")
public class ListBuildRequestsCommand implements Runnable {

    @Inject
    KubernetesClient kubernetesClient;

    @Override
    public void run() {
        try {
            List<ArtifactBuildRequest> items = kubernetesClient.resources(ArtifactBuildRequest.class).list().getItems();
            int count = 0;
            for (var request : items) {
                GAV gav = GAV.parse(request.getSpec().getGav());
                if (request.getStatus().getState() == ArtifactBuildRequestStatus.State.MISSING) {
                    System.out.println(gav + " " + request.getStatus().getMessage());
                    count++;
                }
            }
            System.out.println("COUNT: " + count);
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build requests");
        }
    }
}

package com.redhat.hacbs.analyser.kube;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildRequest;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import picocli.CommandLine;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

@CommandLine.Command(name = "list-build-requests")
public class ListBuildRequestsCommand implements Runnable {

    @Inject
    KubernetesClient kubernetesClient;

    @Override
    public void run() {
        try {
            List<ArtifactBuildRequest> items = kubernetesClient.resources(ArtifactBuildRequest.class).list().getItems();
            Map<String, List<String>> results = new TreeMap<>();
            Map<String, AtomicInteger> counts = new TreeMap<>();
            for (var request : items) {
                results.computeIfAbsent(request.getStatus().getState(), s -> new ArrayList<>()).add(request.getSpec().getGav());
                counts.computeIfAbsent(request.getStatus().getState(), s -> new AtomicInteger()).incrementAndGet();
            }
            for (var request : results.entrySet()) {
                System.out.println(request.getKey() + "  " + counts.get(request.getKey()) + "/" + items.size());
                System.out.println("=============");
                for (var i : request.getValue()) {
                    System.out.println(i);
                }
                System.out.println();
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build requests");
        }
    }
}

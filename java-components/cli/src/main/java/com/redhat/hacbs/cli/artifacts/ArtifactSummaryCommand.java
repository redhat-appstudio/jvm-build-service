package com.redhat.hacbs.cli.artifacts;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "summary", mixinStandardHelpOptions = true, description = "Shows a summary of the state of the ArtifactBuild objects")
public class ArtifactSummaryCommand implements Runnable {

    @Override
    public void run() {
        var client = Arc.container().instance(KubernetesClient.class).get();
        var list = client.resources(ArtifactBuild.class).list().getItems();
        Map<String, AtomicInteger> counts = new HashMap<>();
        counts.put(ModelConstants.ARTIFACT_BUILD_COMPLETE, new AtomicInteger());
        counts.put(ModelConstants.ARTIFACT_BUILD_MISSING, new AtomicInteger());
        counts.put(ModelConstants.ARTIFACT_BUILD_FAILED, new AtomicInteger());
        list.forEach(s -> counts.computeIfAbsent(s.getStatus().getState(), (k) -> new AtomicInteger()).incrementAndGet());
        System.out.println("Complete:\t" + counts.remove(ModelConstants.ARTIFACT_BUILD_COMPLETE).get());
        System.out.println("Missing:\t" + counts.remove(ModelConstants.ARTIFACT_BUILD_MISSING).get());
        System.out.println("Failed:\t\t" + counts.remove(ModelConstants.ARTIFACT_BUILD_FAILED).get());
        for (var e : counts.entrySet()) {
            System.out.println(e.getKey().substring("ArtifactBuild".length()) + ":\t" + e.getValue().get());
        }

    }

}

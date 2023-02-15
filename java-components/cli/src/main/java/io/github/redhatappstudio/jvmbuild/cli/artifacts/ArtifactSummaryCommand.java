package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import static com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild.COMPLETE;
import static com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild.FAILED;
import static com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild.MISSING;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "summary")
public class ArtifactSummaryCommand implements Runnable {

    @Override
    public void run() {
        var client = Arc.container().instance(KubernetesClient.class).get();
        var list = client.resources(ArtifactBuild.class).list().getItems();
        Map<String, AtomicInteger> counts = new HashMap<>();
        counts.put(COMPLETE, new AtomicInteger());
        counts.put(MISSING, new AtomicInteger());
        counts.put(FAILED, new AtomicInteger());
        list.forEach(s -> counts.computeIfAbsent(s.getStatus().getState(), (k) -> new AtomicInteger()).incrementAndGet());
        System.out.println("Complete:\t" + counts.remove(COMPLETE).get());
        System.out.println("Missing:\t" + counts.remove(MISSING).get());
        System.out.println("Failed:\t\t" + counts.remove(FAILED).get());
        for (var e : counts.entrySet()) {
            System.out.println(e.getKey().substring("ArtifactBuildState".length()) + ":\t" + e.getValue().get());
        }

    }

}

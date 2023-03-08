package io.github.redhatappstudio.jvmbuild.cli.builds;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "summary", mixinStandardHelpOptions = true, description = "Displays a summary of the build status.")
public class BuildSummaryCommand implements Runnable {

    @Override
    public void run() {
        var client = Arc.container().instance(KubernetesClient.class).get();
        var list = client.resources(DependencyBuild.class).list().getItems();
        Map<String, AtomicInteger> counts = new HashMap<>();
        counts.put("DependencyBuildStateComplete", new AtomicInteger());
        counts.put("DependencyBuildStateFailed", new AtomicInteger());
        list.forEach(s -> counts.computeIfAbsent(s.getStatus().getState(), (k) -> new AtomicInteger()).incrementAndGet());
        System.out.println("Complete:\t" + counts.remove("DependencyBuildStateComplete").get());
        System.out.println("Failed:\t\t" + counts.remove("DependencyBuildStateFailed").get());
        for (var e : counts.entrySet()) {
            System.out.println(e.getKey().substring("DependencyBuildState".length()) + ":\t" + e.getValue().get());
        }

    }

}

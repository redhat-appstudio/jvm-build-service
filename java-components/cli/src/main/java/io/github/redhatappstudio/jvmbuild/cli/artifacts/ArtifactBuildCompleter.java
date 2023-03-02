package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import java.util.Map;
import java.util.stream.Collectors;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.RequestScopedCompleter;
import io.quarkus.arc.Arc;

/**
 * A completer that returns all ArtifactBuild names
 */
public class ArtifactBuildCompleter extends RequestScopedCompleter {

    public static Map<String, ArtifactBuild> createNames() {
        KubernetesClient client = Arc.container().instance(KubernetesClient.class).get();
        return Map.ofEntries(client.resources(ArtifactBuild.class).list().getItems().stream()
                .map(s -> Map.entry(s.getMetadata().getName(), s)).toArray((i) -> new Map.Entry[i]));
    }

    @Override
    public Iterable<String> completionCandidates(KubernetesClient client) {
        return client.resources(ArtifactBuild.class).list().getItems().stream().map(s -> s.getMetadata().getName())
                .collect(Collectors.toList());
    }

}

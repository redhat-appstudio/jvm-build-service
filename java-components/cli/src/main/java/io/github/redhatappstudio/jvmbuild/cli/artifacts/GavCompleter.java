package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import java.util.Map;
import java.util.stream.Collectors;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.RequestScopedCompleter;
import io.quarkus.arc.Arc;

/**
 * Completer that selects artifacts by GAV
 */
public class GavCompleter extends RequestScopedCompleter {

    @Override
    protected Iterable<String> completionCandidates(KubernetesClient client) {
        return client.resources(ArtifactBuild.class).list().getItems().stream().map(s -> s.getSpec().getGav())
                .collect(Collectors.toList());
    }

    public static Map<String, ArtifactBuild> createNames() {
        KubernetesClient client = Arc.container().instance(KubernetesClient.class).get();
        return Map.ofEntries(client.resources(ArtifactBuild.class).list().getItems().stream()
                .map(s -> Map.entry(s.getSpec().getGav(), s)).toArray((i) -> new Map.Entry[i]));
    }
}

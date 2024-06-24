package com.redhat.hacbs.cli.rebuilt;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.redhat.hacbs.common.tools.completer.RequestScopedCompleter;
import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifact;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;

/**
 * A completer that returns all {@link com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifact} names
 */
public class RebuildCompleter
        extends RequestScopedCompleter {

    public static Map<String, RebuiltArtifact> createNames() {
        try (InstanceHandle<KubernetesClient> instanceHandle = Arc.container().instance(KubernetesClient.class)) {
            KubernetesClient client = instanceHandle.get();
            return client.resources(RebuiltArtifact.class)
                    .list()
                    .getItems()
                    .stream()
                    .collect(Collectors.toMap(x -> x.getMetadata().getName(), Function.identity(),
                            (k1, k2) -> k1, TreeMap::new));
        }
    }

    @Override
    public Iterable<String> completionCandidates(KubernetesClient client) {
        return client.resources(RebuiltArtifact.class).list().getItems().stream().map(s -> s.getMetadata().getName())
                .collect(Collectors.toList());
    }
}

package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import java.util.stream.Collectors;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.RequestScopedCompleter;

/**
 * A completer that returns all ArtifactBuild names
 */
public class MissingArtifactBuildCompleter extends RequestScopedCompleter {

    @Override
    public Iterable<String> completionCandidates(KubernetesClient client) {
        return client.resources(ArtifactBuild.class).list().getItems().stream()
                .filter(s -> s.getStatus().getState().equals(ArtifactBuild.MISSING)).map(s -> s.getMetadata().getName())
                .collect(Collectors.toList());
    }

}

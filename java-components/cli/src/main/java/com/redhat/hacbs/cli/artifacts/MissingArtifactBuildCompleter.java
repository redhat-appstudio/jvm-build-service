package com.redhat.hacbs.cli.artifacts;

import java.util.stream.Collectors;

import com.redhat.hacbs.common.tools.completer.RequestScopedCompleter;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * A completer that returns all ArtifactBuild names
 */
public class MissingArtifactBuildCompleter extends RequestScopedCompleter {

    @Override
    public Iterable<String> completionCandidates(KubernetesClient client) {
        return client.resources(ArtifactBuild.class).list().getItems().stream()
                .filter(s -> s.getStatus().getState().equals(ModelConstants.ARTIFACT_BUILD_MISSING))
                .map(s -> s.getMetadata().getName())
                .collect(Collectors.toList());
    }

}

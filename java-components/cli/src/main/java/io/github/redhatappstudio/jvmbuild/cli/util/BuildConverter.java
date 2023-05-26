package io.github.redhatappstudio.jvmbuild.cli.util;

import java.util.Optional;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.KubernetesClient;

public class BuildConverter {
    public static DependencyBuild buildToArtifact(KubernetesClient client, ArtifactBuild ab) {
        if (ab == null) {
            return null;
        }
        for (var i : client.resources(DependencyBuild.class).list().getItems()) {
            Optional<OwnerReference> ownerReferenceFor = i.getOwnerReferenceFor(ab);
            if (ownerReferenceFor.isPresent()) {
                return i;
            }
        }
        return null;
    }
}

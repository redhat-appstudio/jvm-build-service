package com.redhat.hacbs.resources.model.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group(ModelConstants.GROUP)
@Version(ModelConstants.VERSION)
@JsonInclude(Include.NON_NULL)
public class ArtifactBuildRequest extends CustomResource<ArtifactBuildRequestSpec, ArtifactBuildRequestStatus>
        implements Namespaced {

    @Override
    protected ArtifactBuildRequestSpec initSpec() {
        return new ArtifactBuildRequestSpec();
    }

    @Override
    protected ArtifactBuildRequestStatus initStatus() {
        return new ArtifactBuildRequestStatus();
    }
}

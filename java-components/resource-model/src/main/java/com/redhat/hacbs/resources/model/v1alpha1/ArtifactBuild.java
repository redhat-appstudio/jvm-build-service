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
public class ArtifactBuild extends CustomResource<ArtifactBuildSpec, ArtifactBuildStatus>
        implements Namespaced {

    public static final String COMPLETE = "ArtifactBuildComplete";
    public static final String NEW = "ArtifactBuildNew";
    public static final String DISCOVERING = "ArtifactBuildDiscovering";
    public static final String MISSING = "ArtifactBuildMissing";
    public static final String BUILDING = "ArtifactBuildBuilding";
    public static final String FAILED = "ArtifactBuildFailed";

    @Override
    protected ArtifactBuildSpec initSpec() {
        return new ArtifactBuildSpec();
    }

    @Override
    protected ArtifactBuildStatus initStatus() {
        return new ArtifactBuildStatus();
    }
}

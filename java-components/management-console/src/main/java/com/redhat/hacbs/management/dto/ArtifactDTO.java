package com.redhat.hacbs.management.dto;

import java.util.Objects;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.management.model.StoredArtifactBuild;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

public record ArtifactDTO(
        @Schema(required = true) long id,
        @Schema(required = true) String name,
        @Schema(required = true) String gav,
        @Schema(required = true) String scmRepo,
        @Schema(required = true) String tag,
        @Schema(required = true) String commit,
        String contextPath,
        String dependencyBuildName,
        long dependencyBuildId,
        boolean succeeded,
        boolean missing,
        String message) {
    public static ArtifactDTO of(StoredArtifactBuild build, StoredDependencyBuild dependencyBuild) {
        return new ArtifactDTO(
                build.id,
                build.name,
                build.mavenArtifact.gav(),
                build.buildIdentifier.repository.url,
                build.buildIdentifier.tag,
                build.buildIdentifier.hash,
                build.buildIdentifier.contextPath,
                build.buildIdentifier.dependencyBuildName,
                dependencyBuild.id,
                Objects.equals(build.state, ModelConstants.ARTIFACT_BUILD_COMPLETE),
                Objects.equals(build.state, ModelConstants.ARTIFACT_BUILD_MISSING),
                build.message);

    }
}

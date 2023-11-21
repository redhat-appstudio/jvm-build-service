package com.redhat.hacbs.management.dto;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.StoredDependencyBuild;

public record BuildDTO(
        @Schema(required = true) long id,
        @Schema(required = true) String name,
        String scmRepo,
        String tag,
        String commit,
        String contextPath,
        boolean succeeded,
        boolean contaminated,
        List<String> artifacts,
        BuildAttemptDTO successfulBuild,
        List<BuildAttemptDTO> buildAttempts) {
    public static BuildDTO of(StoredDependencyBuild build) {
        BuildAttemptDTO success = build.buildAttempts.stream().filter(s -> s.successful).findFirst().map(BuildAttemptDTO::of)
                .orElse(null);
        List<BuildAttemptDTO> others = build.buildAttempts.stream().filter(s -> success == null || s.id != success.id())
                .map(BuildAttemptDTO::of).toList();
        return new BuildDTO(
                build.id,
                build.buildIdentifier.dependencyBuildName,
                build.buildIdentifier.repository.url,
                build.buildIdentifier.tag,
                build.buildIdentifier.hash,
                build.buildIdentifier.contextPath,
                build.succeeded,
                build.contaminated,
                build.producedArtifacts.stream().map(MavenArtifact::gav).toList(),
                success,
                others);

    }
}

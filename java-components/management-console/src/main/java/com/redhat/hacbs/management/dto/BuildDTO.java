package com.redhat.hacbs.management.dto;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.ShadingDetails;
import com.redhat.hacbs.management.model.StoredDependencyBuild;

import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;

public record BuildDTO(
        @Schema(required = true) long id,
        @Schema(required = true) String name,
        @Schema(required = true) String scmRepo,
        @Schema(required = true) String tag,
        @Schema(required = true) String commit,
        String contextPath,
        @Schema(required = true) boolean succeeded,
        @Schema(required = true) boolean contaminated,
        @Schema(required = true) boolean verified,
        List<String> artifacts,
        BuildAttemptDTO successfulBuild,
        List<BuildAttemptDTO> buildAttempts,
        List<ShadingDetails> shadingDetails,

        @Schema(required = true) boolean inQueue,
        @Schema(required = true) long buildSbomDependencySetId) {
    public static BuildDTO of(StoredDependencyBuild build) {
        EntityManager entityManager = Arc.container().instance(EntityManager.class).get();
        var inQueue = false;
        Long n = (Long) entityManager.createQuery(
                "select count(a) from StoredArtifactBuild a inner join BuildQueue b on b.mavenArtifact=a.mavenArtifact where a.buildIdentifier=:b")
                .setParameter("b", build.buildIdentifier).getSingleResult();
        if (n > 0) {
            inQueue = true;
        }

        BuildAttempt success = null;
        for (var b : build.buildAttempts) {
            if (b.successful) {
                if (success == null) {
                    success = b;
                } else {
                    if (b.startTime != null && (success.startTime == null || success.startTime.isBefore(b.startTime))) {
                        success = b;
                    }
                }
            }
        }
        BuildAttemptDTO successfulBuild = success == null ? null : BuildAttemptDTO.of(success);

        if (successfulBuild != null) {
            Log.infof("Build maven repo %s", successfulBuild.mavenRepository());
        }
        List<BuildAttemptDTO> others = build.buildAttempts.stream()
                .filter(s -> successfulBuild == null || s.id != successfulBuild.id())
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
                successfulBuild == null || successfulBuild.passedVerification(),
                build.producedArtifacts.stream().map(MavenArtifact::gav).toList(),
                successfulBuild,
                others,
                build.shadingDetails,
                inQueue,
                success != null
                        ? (success.buildSbom != null && success.buildSbom.dependencySet != null
                                ? success.buildSbom.dependencySet.id
                                : -1)
                        : -1);

    }
}

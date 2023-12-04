package com.redhat.hacbs.management.dto;

import java.util.List;

import jakarta.persistence.EntityManager;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.StoredDependencyBuild;

import io.quarkus.arc.Arc;

public record BuildDTO(
        @Schema(required = true) long id,
        @Schema(required = true) String name,
        @Schema(required = true) String scmRepo,
        @Schema(required = true) String tag,
        @Schema(required = true) String commit,
        String contextPath,
        boolean succeeded,
        boolean contaminated,
        List<String> artifacts,
        BuildAttemptDTO successfulBuild,
        List<BuildAttemptDTO> buildAttempts,

        boolean inQueue) {
    public static BuildDTO of(StoredDependencyBuild build) {
        EntityManager entityManager = Arc.container().instance(EntityManager.class).get();
        var inQueue = false;
        Long n = (Long) entityManager.createQuery(
                "select count(*) from StoredArtifactBuild a inner join BuildQueue b on b.mavenArtifact=a.mavenArtifact where a.buildIdentifier=:b")
                .setParameter("b", build.buildIdentifier).getSingleResult();
        if (n > 0) {
            inQueue = true;
        }

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
                others,
                inQueue);

    }
}

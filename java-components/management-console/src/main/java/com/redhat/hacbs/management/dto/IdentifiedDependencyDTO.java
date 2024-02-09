package com.redhat.hacbs.management.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.NoResultException;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.management.model.DependencySet;
import com.redhat.hacbs.management.model.StoredDependencyBuild;

public record IdentifiedDependencyDTO(
        @Schema(required = true) String gav,
        @Schema(required = true) String source,
        String dependencyBuildIdentifier,
        String buildAttemptId,
        @Schema(required = true) boolean inQueue,
        @Schema(required = true) boolean buildSuccess,
        @Schema(required = true) Map<String, String> attributes) implements Comparable<IdentifiedDependencyDTO> {

    @Override
    public int compareTo(IdentifiedDependencyDTO o) {
        return gav.compareTo(o.gav);
    }

    public static List<IdentifiedDependencyDTO> fromDependencySet(DependencySet dependencySet) {
        List<IdentifiedDependencyDTO> depList = new ArrayList<>();
        //TODO: this is slow as hell
        for (var dep : dependencySet.dependencies) {
            Map<String, String> attributes = new HashMap<>();
            if (dep.attributes != null) {
                for (var s : dep.attributes.split(";")) {
                    var parts = s.split("=");
                    attributes.put(parts[0], parts[1]);
                }
            }
            String buildId = null;
            boolean buildSuccess = false;
            if (dep.buildId == null) {
                try {
                    StoredDependencyBuild db = StoredDependencyBuild.findByArtifact(dep.mavenArtifact);
                    if (db != null) {
                        buildId = db.buildIdentifier.dependencyBuildName;
                        buildSuccess = db.succeeded;
                    }
                } catch (NoResultException ignore) {
                }
            } else {
                BuildAttempt db = BuildAttempt.find("buildId", dep.buildId).firstResult();
                if (db != null) {
                    buildId = db.dependencyBuild.buildIdentifier.dependencyBuildName;
                    buildSuccess = db.dependencyBuild.succeeded;
                }
            }
            boolean inQueue = BuildQueue.inBuildQueue(dep.mavenArtifact);
            IdentifiedDependencyDTO d = new IdentifiedDependencyDTO(dep.mavenArtifact.gav(), dep.source, buildId, dep.buildId,
                    inQueue, buildSuccess, attributes);
            depList.add(d);
        }
        Collections.sort(depList);
        return depList;
    }

}

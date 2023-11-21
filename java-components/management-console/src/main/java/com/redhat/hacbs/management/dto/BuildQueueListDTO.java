package com.redhat.hacbs.management.dto;

import com.redhat.hacbs.management.model.BuildQueue;

public record BuildQueueListDTO(String artifact, boolean priority) {

    public static BuildQueueListDTO of(BuildQueue buildQueue) {
        return new BuildQueueListDTO(buildQueue.mavenArtifact.gav(), buildQueue.priority);
    }
}

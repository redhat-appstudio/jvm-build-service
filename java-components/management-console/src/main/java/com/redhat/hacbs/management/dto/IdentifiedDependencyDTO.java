package com.redhat.hacbs.management.dto;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record IdentifiedDependencyDTO(
        @Schema(required = true) String gav,
        @Schema(required = true) String source, Long build, @Schema(required = true) boolean inQueue,
        @Schema(required = true) boolean buildSuccess,
        @Schema(required = true) Map<String, String> attributes) implements Comparable<IdentifiedDependencyDTO> {

    @Override
    public int compareTo(IdentifiedDependencyDTO o) {
        return gav.compareTo(o.gav);
    }

}

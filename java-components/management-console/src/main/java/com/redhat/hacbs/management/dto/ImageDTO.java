package com.redhat.hacbs.management.dto;

import java.util.List;
import java.util.Objects;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record ImageDTO(
        @Schema(required = true) String repository,
        @Schema(required = true) String tag,
        @Schema(required = true) String digest,
        @Schema(required = true) boolean analysisComplete,
        List<IdentifiedDependencyDTO> dependencies) {

    @Schema(required = true)
    public long getTotalDependencies() {
        return dependencies.size();
    }

    @Schema(required = true)
    public long getUntrustedDependencies() {
        return dependencies.stream()
                .filter(s -> !(Objects.equals(s.source(), "rebuilt") || Objects.equals(s.source(), "redhat"))).count();
    }

    @Schema(required = true)
    public long getTrustedDependencies() {
        return dependencies.stream()
                .filter(s -> Objects.equals(s.source(), "rebuilt") || Objects.equals(s.source(), "redhat")).count();
    }

    @Schema(required = true)
    public long getAvailableBuilds() {
        return dependencies.stream()
                .filter(s -> !(Objects.equals(s.source(), "rebuilt") || Objects.equals(s.source(), "redhat")))
                .filter(s -> s.buildSuccess()).count();
    }

    public String getFullName() {
        return repository + (tag != null ? ":" + tag : "") + (digest != null ? "@" + digest : "");
    }

}

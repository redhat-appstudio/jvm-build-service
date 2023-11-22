package com.redhat.hacbs.management.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public class DeploymentDTO {
    @Schema(required = true)
    public String namespace;
    @Schema(required = true)
    public String name;
    @Schema(required = true)
    public boolean analysisComplete;
    @Schema(required = true)
    public List<Image> images = new ArrayList<>();

    public record Image(
            @Schema(required = true) String string,
            @Schema(required = true) boolean analysisComplete, List<Dependency> dependencies) {

        @Schema(required = true)
        public long getTotalDependencies() {
            return dependencies.size();
        }

        @Schema(required = true)
        public long getUntrustedDependencies() {
            return dependencies.stream()
                    .filter(s -> !(Objects.equals(s.source, "rebuild") || Objects.equals(s.source, "redhat"))).count();
        }

        @Schema(required = true)
        public long getTrustedDependencies() {
            return dependencies.stream()
                    .filter(s -> Objects.equals(s.source, "rebuild") || Objects.equals(s.source, "redhat")).count();
        }

        @Schema(required = true)
        public long getAvailableBuilds() {
            return dependencies.stream()
                    .filter(s -> !(Objects.equals(s.source, "rebuild") || Objects.equals(s.source, "redhat")))
                    .filter(s -> s.buildSuccess).count();
        }
    }

    public record Dependency(
            @Schema(required = true) String gav,
            @Schema(required = true) String source, Long build, @Schema(required = true) boolean inQueue,
            @Schema(required = true) boolean buildSuccess,
            @Schema(required = true) Map<String, String> attributes) implements Comparable<Dependency> {

        @Override
        public int compareTo(Dependency o) {
            return gav.compareTo(o.gav);
        }

    }
}

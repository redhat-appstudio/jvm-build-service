package com.redhat.hacbs.management.dto;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.ShadingDetails;

import io.quarkus.arc.Arc;

public record BuildAttemptDTO(
        @Schema(required = true) long id,
        @Schema(required = true) String buildId,
        @Schema(required = true) String label,
        String jdk,
        String mavenVersion,
        String gradleVersion,
        String sbtVersion,
        String antVersion,
        String tool,
        String builderImage,
        String preBuildImage,
        String hermeticBuilderImage,
        String outputImage,
        String outputImageDigest,
        String commandLine,
        String preBuildScript,
        String postBuildScript,
        String enforceVersion,
        boolean disableSubModules,
        int additionalMemory,
        String repositories,
        String allowedDifferences,
        String buildLogsUrl,
        String buildPipelineUrl,
        String mavenRepository,
        boolean successful,
        boolean passedVerification,
        boolean contaminated,
        @Schema(required = true) Map<String, List<String>> upstreamDifferences,
        String gitArchiveSha,
        String gitArchiveTag,
        String gitArchiveUrl,
        String diagnosticDockerFile,
        Instant startTime,

        List<ShadingDetails> shadingDetails,
        List<String> artifacts,
        @Schema(required = true) long buildSbomDependencySetId) {

    public static BuildAttemptDTO of(BuildAttempt i) {
        var label = "JDK " + i.jdk;
        switch (i.tool) {
            case "maven":
                label += " Maven " + i.mavenVersion;
                break;
            case "gradle":
                label += " Gradle " + i.gradleVersion;
                break;
            case "sbt":
                label += " SBT " + i.sbtVersion;
                break;
            case "ant":
                label += " Ant " + i.antVersion;
                break;
        }
        label += " " + DateTimeFormatter.ISO_INSTANT.format(i.startTime);
        return new BuildAttemptDTO(
                i.id,
                i.buildId,
                label,
                i.jdk,
                i.mavenVersion,
                i.gradleVersion,
                i.sbtVersion,
                i.antVersion,
                i.tool,
                i.builderImage,
                i.preBuildImage,
                i.hermeticBuilderImage,
                i.outputImage,
                i.outputImageDigest,
                i.commandLine,
                i.preBuildScript,
                i.postBuildScript,
                i.enforceVersion,
                i.disableSubModules,
                i.additionalMemory,
                i.repositories,
                i.allowedDifferences,
                i.buildLogsUrl,
                i.buildPipelineUrl,
                i.mavenRepository,
                i.successful,
                i.passedVerification,
                i.contaminated,
                mapDifferences(i.upstreamDifferences),
                i.gitArchiveSha,
                i.gitArchiveTag,
                i.gitArchiveUrl,
                i.diagnosticDockerFile,
                i.startTime,
                i.shadingDetails,
                i.producedArtifacts.stream().map(MavenArtifact::gav).toList(),
                i.buildSbom != null && i.buildSbom.dependencySet != null
                        ? i.buildSbom.dependencySet.id
                        : -1);
    }

    private static Map<String, List<String>> mapDifferences(String upstreamDifferences) {
        if (upstreamDifferences == null) {
            return Map.of();
        }
        ObjectMapper mapper = Arc.container().instance(ObjectMapper.class).get();
        Map<String, List<String>> ret = null;
        try {
            ret = mapper.readValue(upstreamDifferences,
                    TypeFactory.defaultInstance().constructMapType(HashMap.class, String.class, List.class));
            var it = ret.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().isEmpty()) {
                    it.remove();
                }
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return ret;
    }
}

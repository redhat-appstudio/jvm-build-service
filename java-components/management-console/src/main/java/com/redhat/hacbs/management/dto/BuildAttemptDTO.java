package com.redhat.hacbs.management.dto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.redhat.hacbs.management.model.BuildAttempt;

import io.quarkus.arc.Arc;

public record BuildAttemptDTO(
        @Schema(required = true) long id,
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
        //List<AdditionalDownload> additionalDownloads,

        boolean successful,
        boolean passedVerification,

        //StoredDependencyBuild dependencyBuild,

        //List<BuildFile> storedBuildResults,
        @Schema(required = true) Map<String, List<String>> upstreamDifferences,
        String gitArchiveSha,
        String gitArchiveTag,
        String gitArchiveUrl) {

    public static BuildAttemptDTO of(BuildAttempt i) {
        return new BuildAttemptDTO(
                i.id,
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
                mapDifferences(i.upstreamDifferences),
                i.gitArchiveSha,
                i.gitArchiveTag,
                i.gitArchiveUrl);
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

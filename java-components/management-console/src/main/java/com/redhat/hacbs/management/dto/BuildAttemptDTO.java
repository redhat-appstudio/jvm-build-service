package com.redhat.hacbs.management.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.management.model.BuildAttempt;

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

        //List<AdditionalDownload> additionalDownloads,

        boolean successful,
        boolean passedVerification,

        //StoredDependencyBuild dependencyBuild,

        //List<BuildFile> storedBuildResults,
        String upstreamDifferences,
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
                i.successful,
                i.passedVerification,
                i.upstreamDifferences,
                i.gitArchiveSha,
                i.gitArchiveTag,
                i.gitArchiveUrl);
    }
}

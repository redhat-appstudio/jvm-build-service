package com.redhat.hacbs.management.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record GithubBuildDTO(
        @Schema(required = true) long id,
        @Schema(required = true) String name,
        @Schema(required = true) boolean buildsComponent,
        @Schema(required = true) String url,
        @Schema(required = true) Long dependencySetId,
        @Schema(required = true) Long buildDependencySetId) {

}

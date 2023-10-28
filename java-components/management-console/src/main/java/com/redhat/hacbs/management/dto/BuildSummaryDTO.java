package com.redhat.hacbs.management.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record BuildSummaryDTO(@Schema(required = true) long totalBuilds, @Schema(required = true) long successfulBuilds,
        @Schema(required = true) long contaminatedBuilds, @Schema(required = true) long runningBuilds,
        @Schema(required = true) long failingBuilds) {

}

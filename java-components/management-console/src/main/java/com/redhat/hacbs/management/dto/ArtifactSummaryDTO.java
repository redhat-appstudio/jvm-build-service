package com.redhat.hacbs.management.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record ArtifactSummaryDTO(@Schema(required = true) long built, @Schema(required = true) long missing,
        @Schema(required = true) long failed, @Schema(required = true) long total) {

}

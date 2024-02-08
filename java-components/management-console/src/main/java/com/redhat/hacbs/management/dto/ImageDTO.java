package com.redhat.hacbs.management.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record ImageDTO(
        @Schema(required = true) String repository,
        @Schema(required = true) String tag,
        @Schema(required = true) String digest,
        @Schema(required = true) boolean analysisComplete,
        @Schema(required = true) long dependencySet) {

    public String getFullName() {
        return repository + (tag != null ? ":" + tag : "") + (digest != null ? "@" + digest : "");
    }

}

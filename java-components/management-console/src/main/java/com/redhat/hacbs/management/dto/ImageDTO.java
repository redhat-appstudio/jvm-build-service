package com.redhat.hacbs.management.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.management.model.ContainerImage;

public record ImageDTO(
        @Schema(required = true) String repository,
        @Schema(required = true) String tag,
        @Schema(required = true) String digest,
        @Schema(required = true) boolean analysisComplete,
        @Schema(required = true) boolean analysisFailed,
        @Schema(required = true) long dependencySet) {

    // Called by 'image.image.fullName' in ImageList.tsx
    public String getFullName() {
        return repository + (tag != null ? ":" + tag : "") + (digest != null ? "@" + digest : "");
    }

    public static ImageDTO of(ContainerImage image) {
        return new ImageDTO(image.repository.repository, image.tag, image.digest, image.analysisComplete, image.analysisFailed,
                image.dependencySet.id);
    }
}

package com.redhat.hacbs.management.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record BuildListDTO(
        @Schema(required = true) long id,
        @Schema(required = true) String name,
        String scmRepo, String tag,
        boolean succeeded,
        boolean contaminated,
        String artifacts,
        boolean inQueue,
        @Schema(required = true) long creationTime) {

}

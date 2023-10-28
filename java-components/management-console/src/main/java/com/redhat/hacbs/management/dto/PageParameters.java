package com.redhat.hacbs.management.dto;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public record PageParameters<T>(@Schema(required = true) List<T> items, @Schema(required = true) long count,
        @Schema(required = true) long pageNo, @Schema(required = true) long perPage) {

}

package com.redhat.hacbs.management.dto;

import java.time.Instant;

public record RunningBuildDTO(String description, String status, Instant startTime) {

}

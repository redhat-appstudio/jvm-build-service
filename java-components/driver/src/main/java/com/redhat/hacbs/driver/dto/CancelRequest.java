package com.redhat.hacbs.driver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Builder;

@Builder(builderClassName = "Builder")
@JsonIgnoreProperties(ignoreUnknown = true)
public record CancelRequest(String pipelineId, String namespace) {

}

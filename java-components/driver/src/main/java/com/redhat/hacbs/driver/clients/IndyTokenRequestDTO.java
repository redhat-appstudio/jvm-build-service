package com.redhat.hacbs.driver.clients;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;

/**
 * DTO of the Indy token endpoint request
 */
@Builder
public record IndyTokenRequestDTO(@JsonProperty("build-id") String buildId) {

}

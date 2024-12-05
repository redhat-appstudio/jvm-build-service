package com.redhat.hacbs.driver.clients;

import lombok.Builder;

/**
 * DTO of the Indy token endpoint response
 */
@Builder
public record IndyTokenResponseDTO(String token) {

}

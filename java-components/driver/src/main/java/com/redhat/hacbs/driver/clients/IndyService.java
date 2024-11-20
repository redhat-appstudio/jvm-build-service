/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.hacbs.driver.clients;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Indy service representing the Indy server. It uses Quarkus magical rest client to generate the client implementation
 */
@RegisterRestClient(configKey = "indy-service")
public interface IndyService {

    /**
     * Ask Indy to give us the token that we will use for Maven communication with Indy, in the builder pod for the
     * particular buildId
     *
     * @param indyTokenRequestDTO the DTO to send to Indy
     * @param accessToken accessToken required to send data. Note that it should include "Bearer <token>"
     *
     * @return Token DTO
     */
    @Path("/api/security/auth/token")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @POST
    public IndyTokenResponseDTO getAuthToken(
            IndyTokenRequestDTO indyTokenRequestDTO,
            @HeaderParam("Authorization") String accessToken);
}

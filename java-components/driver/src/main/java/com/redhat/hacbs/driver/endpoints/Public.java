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

package com.redhat.hacbs.driver.endpoints;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.pnc.api.dto.ComponentVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.hacbs.driver.Driver;
import com.redhat.hacbs.driver.dto.BuildRequest;
import com.redhat.hacbs.driver.dto.BuildResponse;
import com.redhat.hacbs.driver.util.Info;

import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * Endpoint to start/cancel the build.
 *
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@Path("/")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class Public {

    private static final Logger logger = LoggerFactory.getLogger(Public.class);

    @Inject
    Driver driver;

    @Inject
    Info info;

    @POST
    @Path("/build")
    @RunOnVirtualThread
    //    public CompletionStage<BuildResponse> build(BuildRequest buildRequest) {
    public BuildResponse build(BuildRequest buildRequest) {
        logger.info("Requested project build: {}", buildRequest.projectName());
        var result = driver.create(buildRequest);
        logger.info("### Got {}", result);
        return result;
    }

    // TODO: Is delete possible in konflux?
    //
    //    /**
    //     * Cancel the build execution.
    //     */
    //    @PUT
    //    @Path("/cancel")
    //    public CompletionStage<Response> cancel(BuildCancelRequest buildCancelRequest) {
    //        logger.info("Requested cancel: {}", buildCancelRequest.getBuildExecutionId());
    //        return driver.cancel(buildCancelRequest).thenApply((r) -> Response.status(r.getCode()).build());
    //    }

    @Path("/version")
    @GET
    @RunOnVirtualThread
    public ComponentVersion getVersion() {
        var r = info.getVersion();
        logger.info("Requested version {}", r);
        return r;
    }
}

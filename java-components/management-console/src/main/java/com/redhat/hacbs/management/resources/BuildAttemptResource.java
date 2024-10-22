package com.redhat.hacbs.management.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import com.redhat.hacbs.management.model.BuildAttempt;

import io.quarkus.panache.common.Parameters;

@Path("/builds/attempts")
public class BuildAttemptResource extends BuildLogs {

    @GET
    @Path("/logs/{name}")
    public Response logs(@PathParam("name") String name) {
        BuildAttempt attempt = BuildAttempt
                .find("buildId = :buildId",
                        Parameters.with("buildId", name))
                .firstResult();
        if (attempt == null) {
            throw new NotFoundException();
        }
        return extractLog(Type.BUILD, attempt.dependencyBuild.buildIdentifier.dependencyBuildName);
    }
}

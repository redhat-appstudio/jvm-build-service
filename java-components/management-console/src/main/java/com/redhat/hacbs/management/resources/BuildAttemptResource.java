package com.redhat.hacbs.management.resources;

import java.net.URI;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import com.redhat.hacbs.management.model.BuildAttempt;

@Path("/builds/attempts")
public class BuildAttemptResource extends BuildLogs {

    @GET
    @Path("/logs/{id}")
    public Response logs(@PathParam("id") int id) {
        BuildAttempt attempt = BuildAttempt.findById(id);
        if (attempt == null) {
            throw new NotFoundException();
        }
        URI uri = URI.create(attempt.buildLogsUrl);
        return extractLog(Type.BUILD, uri, attempt.dependencyBuild.buildIdentifier.dependencyBuildName);
    }
}

package com.redhat.hacbs.management.resources;

import java.io.InputStream;
import java.net.URI;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.redhat.hacbs.management.model.BuildAttempt;

import io.quarkus.logging.Log;
import software.amazon.awssdk.services.s3.S3Client;

@Path("/builds/attempts")
public class BuildAttemptResource {

    @Inject
    S3Client s3Client;

    @GET
    @Path("/logs/{id}")
    public Response logs(@PathParam("id") int id) {
        BuildAttempt attempt = BuildAttempt.findById(id);
        if (attempt == null) {
            throw new NotFoundException();
        }
        URI uri = URI.create(attempt.buildLogsUrl);

        InputStream stream = s3Client.getObject(b -> {
            String path = uri.getPath().substring(1);
            String bucket = uri.getHost();
            Log.infof("requesting logs %s from bucket %s", path, bucket);
            b.bucket(bucket)
                    .key(path);
        });
        return Response.ok(stream, MediaType.TEXT_PLAIN_TYPE).build();
    }

}

package com.redhat.hacbs.artifactcache.services.client.maven;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

@Path("/")
public interface MavenHttpClient {

    @GET
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    Response getArtifactFile(@PathParam("group") String group, @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target);

    @GET
    @Path("{group:.*?}/{target}")
    Response getMetadataFile(@PathParam("group") String group, @PathParam("target") String target);
}

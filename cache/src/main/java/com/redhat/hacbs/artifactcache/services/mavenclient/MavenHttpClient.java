package com.redhat.hacbs.artifactcache.services.mavenclient;

import java.io.InputStream;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

@Path("/")
public interface MavenHttpClient {

    @GET
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    InputStream getArtifactFile(@PathParam("group") String group, @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target);

    @GET
    @Path("{group:.*?}/{target}")
    InputStream getMetadataFile(@PathParam("group") String group, @PathParam("target") String target);
}

package com.redhat.hacbs.sidecar.services;

import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "cache-service")
@Path("/maven2")
public interface RemoteClient {

    @GET
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    byte[] get(@HeaderParam("X-build-policy") String buildPolicy, @PathParam("group") String group,
            @PathParam("artifact") String artifact, @PathParam("version") String version, @PathParam("target") String target);

    @GET
    @Path("{group:.*?}/maven-metadata.xml{hash:.*?}")
    byte[] get(@HeaderParam("X-build-policy") String buildPolicy, @PathParam("group") String group,
            @PathParam("hash") String target);
}

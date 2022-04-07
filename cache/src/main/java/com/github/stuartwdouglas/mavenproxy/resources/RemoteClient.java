package com.github.stuartwdouglas.mavenproxy.resources;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "remote-maven-repo")
@Path("/maven2")
public interface RemoteClient {

    @GET
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    public byte[] get(@PathParam("group") String group, @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target);

    @GET
    @Path("{group:.*?}/{target}")
    byte[] get(@PathParam("group") String group, @PathParam("target") String target);
}

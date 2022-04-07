package com.github.stuartwdouglas.mavenproxy.resources;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.smallrye.common.annotation.Blocking;

@Path("/maven2")
@Blocking
public class MavenResourceManager {

    final RemoteClient remoteClient;

    public MavenResourceManager(@RestClient RemoteClient remoteClient) {
        this.remoteClient = remoteClient;
    }


    @GET
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    public byte[] get(@PathParam("group") String group, @PathParam("artifact") String artifact, @PathParam("version") String version, @PathParam("target") String target) throws Exception {
        System.out.println(group + "/" + artifact + "/" + version + "/" + target);
        return remoteClient.get(group, artifact, version, target);
    }

    @GET
    @Path("{group:.*?}/{target}")
    public byte[] get(@PathParam("group") String group, @PathParam("target") String target) throws Exception {
        System.out.println(group + "/" + target);
        return remoteClient.get(group, target);
    }

}

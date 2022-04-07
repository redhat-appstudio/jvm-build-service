package com.github.stuartwdouglas.mavenproxy.resources;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;

@Path("/maven2")
@Blocking
public class MavenResourceManager {

    final RemoteClient remoteClient;
    final String buildPolicy;

    public MavenResourceManager(@RestClient RemoteClient remoteClient,
            @ConfigProperty(name = "build-policy") String buildPolicy) {
        this.remoteClient = remoteClient;
        this.buildPolicy = buildPolicy;
        Log.infof("Constructing resource manager with build policy %s", buildPolicy);
    }

    @GET
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    public byte[] get(@PathParam("group") String group, @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target) throws Exception {
        Log.infof("Retrieving artifact %s/%s/%s/%s", group, artifact, version, target);
        return remoteClient.get(buildPolicy, group, artifact, version, target);
    }

    @GET
    @Path("{group:.*?}/{target}")
    public byte[] get(@PathParam("group") String group, @PathParam("target") String target) throws Exception {
        Log.infof("Retrieving file %s/%s", group, target);
        return remoteClient.get(buildPolicy, group, target);
    }

}

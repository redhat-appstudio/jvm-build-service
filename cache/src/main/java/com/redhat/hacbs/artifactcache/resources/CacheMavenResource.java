package com.redhat.hacbs.artifactcache.resources;

import java.io.InputStream;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import com.redhat.hacbs.artifactcache.services.LocalCache;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;

@Path("/maven2")
@Blocking
public class CacheMavenResource {

    final LocalCache cache;

    public CacheMavenResource(LocalCache cache) {
        this.cache = cache;
    }

    @GET
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    public InputStream get(@DefaultValue("default") @HeaderParam("X-build-policy") String buildPolicy,
            @PathParam("group") String group,
            @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target) throws Exception {
        Log.infof("Retrieving artifact %s/%s/%s/%s", group, artifact, version, target);
        var result = cache.getArtifactFile(buildPolicy, group, artifact, version, target);
        if (result.isPresent()) {
            return result.get().getData();
        }
        throw new NotFoundException();
    }

    @GET
    @Path("{group:.*?}/{target}")
    public InputStream get(@DefaultValue("default") @HeaderParam("X-build-policy") String buildPolicy,
            @PathParam("group") String group,
            @PathParam("target") String target) throws Exception {
        Log.infof("Retrieving file %s/%s", group, target);
        var result = cache.getMetadataFile(buildPolicy, group, target);
        if (result.isPresent()) {
            return result.get().getData();
        }
        throw new NotFoundException();
    }

}

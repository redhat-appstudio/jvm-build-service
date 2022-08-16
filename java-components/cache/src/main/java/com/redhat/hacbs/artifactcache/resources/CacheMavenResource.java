package com.redhat.hacbs.artifactcache.resources;

import java.io.InputStream;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

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
    public Response get(@DefaultValue("default") @HeaderParam("X-build-policy") String buildPolicy,
            @HeaderParam("X-build-start") Long buildStartTime,
            @PathParam("group") String group,
            @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target) throws Exception {
        Log.debugf("Retrieving artifact %s/%s/%s/%s", group, artifact, version, target);
        var result = cache.getArtifactFile(buildPolicy, group, artifact, version, target, buildStartTime);
        if (result.isPresent()) {
            var builder = Response.ok(result.get().getData());
            if (result.get().getMetadata().containsKey("maven-repo")) {
                builder.header("X-maven-repo", result.get().getMetadata().get("maven-repo"))
                        .build();
            }
            if (result.get().getSize() > 0) {
                builder.header(HttpHeaders.CONTENT_LENGTH, result.get().getSize());
            }
            return builder.build();
        }
        Log.infof("Failed to get artifact %s/%s/%s/%s", group, artifact, version, target);
        throw new NotFoundException();
    }

    @GET
    @Path("{group:.*?}/maven-metadata.xml{hash:.*?}")
    public InputStream get(@DefaultValue("default") @HeaderParam("X-build-policy") String buildPolicy,
            @PathParam("group") String group,
            @PathParam("hash") String hash) throws Exception {
        Log.debugf("Retrieving file %s/%s", group, "maven-metadata.xml");
        var result = cache.getMetadataFile(buildPolicy, group, "maven-metadata.xml" + hash);
        if (result.isPresent()) {
            return result.get().getData();
        }
        Log.infof("Failed retrieving file %s/%s", group, "maven-metadata.xml");
        throw new NotFoundException();
    }

}

package com.redhat.hacbs.artifactcache.resources;

import java.io.InputStream;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import com.redhat.hacbs.artifactcache.services.Repository;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;

@Path("/maven2")
@Blocking
public class CacheMavenResource {

    final List<Repository> repositories;

    public CacheMavenResource(List<Repository> repositories) {
        this.repositories = repositories;
    }

    @GET
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    public InputStream get(@PathParam("group") String group, @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target) throws Exception {
        Log.infof("Retrieving artifact %s/%s/%s/%s", group, artifact, version, target);
        for (var repo : repositories) {
            var result = repo.getClient().getArtifactFile(group, artifact, version, target);
            if (result.isPresent()) {
                return result.get().getData();
            }
        }
        throw new NotFoundException();
    }

    @GET
    @Path("{group:.*?}/{target}")
    public InputStream get(@PathParam("group") String group, @PathParam("target") String target) throws Exception {
        Log.infof("Retrieving file %s/%s", group, target);
        for (var repo : repositories) {
            var result = repo.getClient().getMetadataFile(group, target);
            if (result.isPresent()) {
                return result.get().getData();
            }
        }
        throw new NotFoundException();
    }

}

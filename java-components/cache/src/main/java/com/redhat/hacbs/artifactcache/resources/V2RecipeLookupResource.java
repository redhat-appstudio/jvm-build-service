package com.redhat.hacbs.artifactcache.resources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.redhat.hacbs.artifactcache.services.RecipeManager;
import com.redhat.hacbs.recipies.GAV;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;

@Path("/v2/recipe-lookup")
@Blocking
public class V2RecipeLookupResource {

    final RecipeManager recipeManager;

    public V2RecipeLookupResource(RecipeManager recipeManager) {
        this.recipeManager = recipeManager;
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("scm-info")
    public Response resolveTagInfo(GAV toBuild) {
        try {
            var result = recipeManager.locator().resolveTagInfo(toBuild);
            return Response.ok(result).build();
        } catch (Exception e) {
            return Response.status(500).entity(e.getMessage()).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("build-info")
    public Response resolveBuildInfo(@QueryParam("scmUrl") String scmUrl, @QueryParam("version") String version) {
        try {
            var result = recipeManager.resolveBuildInfo(scmUrl, version);
            return Response.ok(result).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to lookup build info for %s@%s", scmUrl, version);
            return Response.status(500).entity(e.getMessage()).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("repository-info")
    public Response findRepositories(@QueryParam("repositories") Set<String> repositories)
            throws IOException {
        try {
            repositories = repositories.stream().map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
                    .collect(Collectors.toSet());
            List<String> ret = new ArrayList<>();
            for (var e : recipeManager.getAllRepositoryInfo().entrySet()) {
                var uri = e.getValue().getUri();
                if (uri == null) {
                    continue;
                }
                if (uri.endsWith("/")) {
                    uri = uri.substring(0, uri.length() - 1);
                }
                if (repositories.contains(uri)) {
                    ret.add(e.getKey());
                }
            }
            return Response.ok(ret).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to lookup repository info for %s", repositories);
            return Response.status(500).entity(e.getMessage()).build();
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("build-tool-info")
    public Response lookupBuildToolInfo(@QueryParam("name") String name)
        throws IOException {
        try {
            return Response.ok(recipeManager.getBuildToolInfo(name)).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to lookup build tool info for %s", name);
            return Response.status(500).entity(e.getMessage()).build();
        }
    }
}

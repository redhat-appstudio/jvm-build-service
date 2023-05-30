package com.redhat.hacbs.artifactcache.resources;

import java.io.IOException;

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
    public Response resolveBuildInfo(@QueryParam("scmUrl") String scmUrl, @QueryParam("version") String version)
            throws IOException {
        try {
            var result = recipeManager.resolveBuildInfo(scmUrl, version);
            return Response.ok(result).build();
        } catch (Exception e) {
            return Response.status(500).entity(e.getMessage()).build();
        }

    }
}

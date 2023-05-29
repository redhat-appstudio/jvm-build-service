package com.redhat.hacbs.artifactcache.resources;

import java.io.IOException;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import com.redhat.hacbs.artifactcache.services.RecipeManager;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.build.BuildRecipeInfo;
import com.redhat.hacbs.recipies.scm.TagInfo;

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
    public TagInfo resolveTagInfo(GAV toBuild) {
        return recipeManager.locator().resolveTagInfo(toBuild);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("build-info")
    public BuildRecipeInfo resolveBuildInfo(@QueryParam("scmUrl") String scmUrl, @QueryParam("version") String version)
            throws IOException {
        return recipeManager.resolveBuildInfo(scmUrl, version);

    }
}

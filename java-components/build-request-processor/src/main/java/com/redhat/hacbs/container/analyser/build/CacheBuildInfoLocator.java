package com.redhat.hacbs.container.analyser.build;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.redhat.hacbs.recipies.build.BuildRecipeInfo;

@RegisterRestClient()
@Path("/v2/recipe-lookup/build-info")
public interface CacheBuildInfoLocator {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    BuildRecipeInfo resolveBuildInfo(@QueryParam("scmUrl") String scmUrl, @QueryParam("version") String version);
}

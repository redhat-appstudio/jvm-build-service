package com.redhat.hacbs.container.analyser.build;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.redhat.hacbs.recipies.build.BuildRecipeInfo;

@RegisterRestClient()
@Path("/v2/recipe-lookup/build-info")
public interface CacheBuildInfoLocator {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    BuildRecipeInfo resolveBuildInfo(@QueryParam("scmUrl") String scmUrl, @QueryParam("version") String version);
}

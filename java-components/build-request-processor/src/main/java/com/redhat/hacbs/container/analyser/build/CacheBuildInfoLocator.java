package com.redhat.hacbs.container.analyser.build;

import java.util.List;
import java.util.Set;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.recipes.tools.BuildToolInfo;

@RegisterRestClient()
@Path("/v2/recipe-lookup")
public interface CacheBuildInfoLocator {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("build-info")
    BuildRecipeInfo resolveBuildInfo(@QueryParam("scmUrl") String scmUrl, @QueryParam("version") String version);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("repository-info")
    List<String> findRepositories(@QueryParam("repositories") Set<String> repositories);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("build-tool-info")
    List<BuildToolInfo> lookupBuildToolInfo(@QueryParam("name") String name);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("disabled-plugins")
    List<String> lookupDisabledPlugins(@QueryParam("tool") String tool);
}

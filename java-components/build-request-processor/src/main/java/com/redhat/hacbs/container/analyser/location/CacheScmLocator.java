package com.redhat.hacbs.container.analyser.location;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.redhat.hacbs.recipes.scm.ScmLocator;
import com.redhat.hacbs.recipes.scm.TagInfo;
import com.redhat.hacbs.resources.model.maven.GAV;

@RegisterRestClient()
@Path("/v2/recipe-lookup/scm-info")
public interface CacheScmLocator extends ScmLocator {
    @Override
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public TagInfo resolveTagInfo(GAV toBuild);
}

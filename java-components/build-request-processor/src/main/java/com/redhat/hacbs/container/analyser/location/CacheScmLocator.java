package com.redhat.hacbs.container.analyser.location;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.scm.ScmLocator;
import com.redhat.hacbs.recipies.scm.TagInfo;

@RegisterRestClient()
@Path("/v2/recipe-lookup/scm-info")
public interface CacheScmLocator extends ScmLocator {
    @Override
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public TagInfo resolveTagInfo(GAV toBuild);
}

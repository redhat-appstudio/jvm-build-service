package com.redhat.hacbs.container.analyser.location;

import java.time.temporal.ChronoUnit;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.scm.ScmLocator;
import com.redhat.hacbs.recipies.scm.TagInfo;

@RegisterRestClient()
@Path("/v2/recipe-lookup/scm-info")
@Retry(maxRetries = 2, delay = 10, delayUnit = ChronoUnit.SECONDS)
public interface CacheScmLocator extends ScmLocator {
    @Override
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    TagInfo resolveTagInfo(GAV toBuild);
}

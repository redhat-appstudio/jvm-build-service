package com.redhat.hacbs.analyser.pnc.rest;

import static com.redhat.hacbs.analyser.pnc.rest.SwaggerConstants.MATCH_QUERY_PARAM;
import static com.redhat.hacbs.analyser.pnc.rest.SwaggerConstants.SEARCH_QUERY_PARAM;

import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * @author Jakub Bartecek
 */
@Path("/scm-repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RestClient
public interface SCMRepositoryEndpoint {

    @GET
    Page<SCMRepository> getAll(
            @BeanParam PageParameters pageParameters,
            @QueryParam(MATCH_QUERY_PARAM) String matchUrl,
            @QueryParam(SEARCH_QUERY_PARAM) String searchUrl);

    static final String GET_SPECIFIC_DESC = "Gets a specific SCM repository.";

    /**
     * {@value GET_SPECIFIC_DESC}
     *
     * @param id {@value SCM_ID}
     * @return
     */
    @GET
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON_PATCH_JSON)
    // workaround for PATCH support
    SCMRepository getSpecific(@PathParam("id") String id);

    static final String UPDATE_DESC = "Updates an existing SCM repository.";

    /**
     * {@value UPDATE_DESC}
     *
     * @param id {@value SCM_ID}
     * @param scmRepository
     */
    @PUT
    @Path("/{id}")
    void update(@PathParam("id") String id, @NotNull SCMRepository scmRepository);

    static final String PATCH_SPECIFIC = "Patch an existing SCM repository.";

    /**
     * {@value PATCH_SPECIFIC}
     *
     * @param id {@value SCM_ID}
     * @param scmRepository
     * @return
     */
    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON_PATCH_JSON)
    SCMRepository patchSpecific(
            @PathParam("id") String id,
            @NotNull SCMRepository scmRepository);

}

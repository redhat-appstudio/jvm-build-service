package com.redhat.hacbs.container.results;

import java.util.Map;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@RestClient()
@RegisterRestClient(configKey = "results-service")
@Path("/v2/results-service")
public interface ResultsUpdaterRestClient {

    @POST
    @Path("{name}")
    void updateResults(@PathParam("name") String taskRunName, Map<String, String> results);

}

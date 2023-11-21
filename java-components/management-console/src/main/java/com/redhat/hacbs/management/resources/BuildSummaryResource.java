package com.redhat.hacbs.management.resources;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import com.redhat.hacbs.management.dto.BuildSummaryDTO;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
import com.redhat.hacbs.management.watcher.BuildOrchestrator;

@Path("/builds/status")
public class BuildSummaryResource {

    @Inject
    BuildOrchestrator orchestrator;

    @GET
    public BuildSummaryDTO all(@QueryParam("page") int page, @QueryParam("perPage") int perPage) {
        long all = StoredDependencyBuild.count();
        long successful = StoredDependencyBuild.count("succeeded", true);
        long contaminated = StoredDependencyBuild.count("contaminated", true);
        long failing = all - successful - contaminated;
        int running = orchestrator.getRunningBuilds();
        return new BuildSummaryDTO(all + running, successful, contaminated, running, failing);
    }

}

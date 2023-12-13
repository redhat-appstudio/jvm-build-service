package com.redhat.hacbs.management.resources;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import com.redhat.hacbs.management.dto.RunningBuildDTO;
import com.redhat.hacbs.management.watcher.BuildOrchestrator;

import io.fabric8.kubernetes.client.KubernetesClient;

@Path("/builds/running")
public class RunningBuildsResource {

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    BuildOrchestrator buildOrchestrator;

    @GET
    public List<RunningBuildDTO> all() {
        return buildOrchestrator.getRunningBuildList();
    }

}

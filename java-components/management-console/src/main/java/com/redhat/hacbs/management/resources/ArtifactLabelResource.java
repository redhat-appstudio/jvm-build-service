package com.redhat.hacbs.management.resources;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import com.redhat.hacbs.management.model.ArtifactLabelName;

import io.quarkus.panache.common.Sort;

@Path("/artifact-labels")
public class ArtifactLabelResource {

    @GET
    public List<ArtifactLabelName> names() {
        return ArtifactLabelName.listAll(Sort.by("name"));
    }
}

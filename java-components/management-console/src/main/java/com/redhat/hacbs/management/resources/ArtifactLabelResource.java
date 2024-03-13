package com.redhat.hacbs.management.resources;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import com.redhat.hacbs.management.model.ArtifactLabelName;
import com.redhat.hacbs.management.model.MavenArtifactLabel;

import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;

@Path("/artifact-labels")
public class ArtifactLabelResource {

    @GET
    public List<ArtifactLabelName> names() {
        return ArtifactLabelName.listAll(Sort.by("name"));
    }

    @GET
    @Path("values")
    public List<MavenArtifactLabel> values(@QueryParam("name") String name, @QueryParam("search") String search) {
        return MavenArtifactLabel.find("name=:name", Parameters.with("name", ArtifactLabelName.get(name))).page(0, 10).list();
    }
}

package com.redhat.hacbs.management.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import com.redhat.hacbs.management.dto.ArtifactListDTO;
import com.redhat.hacbs.management.dto.PageParameters;
import com.redhat.hacbs.management.model.StoredArtifactBuild;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

@Path("/artifacts/history")
public class ArtifactHistoryResource {

    //    @Inject
    //    EntityManager entityManager;

    @GET
    public PageParameters<ArtifactListDTO> all(@QueryParam("page") int page, @QueryParam("perPage") int perPage) {
        if (perPage <= 0) {
            perPage = 20;
        }

        var list = StoredArtifactBuild.<StoredArtifactBuild> find("", Sort.descending("creationTimestamp"))
                .page(Page.of(page - 1, perPage)).list();
        List<ArtifactListDTO> ret = new ArrayList<>();
        for (StoredArtifactBuild build : list) {
            ret.add(new ArtifactListDTO(build.mavenArtifact.gav(),
                    Objects.equals(build.status, ModelConstants.ARTIFACT_BUILD_COMPLETE)));
        }

        return new PageParameters<>(ret, StoredDependencyBuild.count(), page, perPage);
    }
}

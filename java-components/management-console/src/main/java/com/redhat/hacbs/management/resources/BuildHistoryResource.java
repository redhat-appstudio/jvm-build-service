package com.redhat.hacbs.management.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import org.eclipse.microprofile.openapi.annotations.Operation;

import com.redhat.hacbs.management.dto.BuildDTO;
import com.redhat.hacbs.management.dto.BuildListDTO;
import com.redhat.hacbs.management.dto.PageParameters;
import com.redhat.hacbs.management.model.StoredArtifactBuild;
import com.redhat.hacbs.management.model.StoredDependencyBuild;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

@Path("/builds/history")
public class BuildHistoryResource {

    @Inject
    EntityManager entityManager;

    @GET
    public PageParameters<BuildListDTO> all(@QueryParam("page") int page, @QueryParam("perPage") int perPage) {
        if (perPage <= 0) {
            perPage = 20;
        }

        var list = StoredDependencyBuild.<StoredDependencyBuild> find("", Sort.descending("creationTime"))
                .page(Page.of(page - 1, perPage)).list();
        List<BuildListDTO> ret = new ArrayList<>();
        for (var build : list) {
            var inQueue = false;
            Long n = (Long) entityManager.createQuery(
                    "select count(*) from StoredArtifactBuild a inner join BuildQueue b on b.mavenArtifact=a.mavenArtifact where a.buildIdentifier=:b")
                    .setParameter("b", build.buildIdentifier).getSingleResult();
            if (n > 0) {
                inQueue = true;
            }
            String artifactList = StoredArtifactBuild.<StoredArtifactBuild> find("buildIdentifier", build.buildIdentifier)
                    .page(0, 5).stream().map(s -> s.mavenArtifact.gav()).collect(Collectors.joining(","));
            ret.add(new BuildListDTO(build.id, build.buildIdentifier.dependencyBuildName, build.buildIdentifier.repository.url,
                    build.buildIdentifier.tag, build.succeeded, build.contaminated, artifactList, inQueue));
        }

        return new PageParameters<>(ret, StoredDependencyBuild.count(), page, perPage);
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "get-build")
    public BuildDTO get(@PathParam("id") long id) {
        StoredDependencyBuild build = StoredDependencyBuild.findById(id);
        if (build == null) {
            throw new NotFoundException();
        }
        return BuildDTO.of(build);
    }

}

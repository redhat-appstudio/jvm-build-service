package com.redhat.hacbs.management.resources;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import com.redhat.hacbs.management.dto.BuildQueueListDTO;
import com.redhat.hacbs.management.dto.PageParameters;
import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.MavenArtifactLabel;
import com.redhat.hacbs.management.model.StoredArtifactBuild;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;

@Path("/builds/queue")
public class BuildQueueResource {

    @Inject
    EntityManager entityManager;

    @GET
    public PageParameters<BuildQueueListDTO> all(@QueryParam("page") int page, @QueryParam("perPage") int perPage) {
        if (perPage <= 0) {
            perPage = 20;
        }
        List<BuildQueueListDTO> list = BuildQueue
                .<BuildQueue> find("", Sort.descending("priority").and("id", Sort.Direction.Descending))
                .page(Page.of(page - 1, perPage)).stream().map(BuildQueueListDTO::of).collect(Collectors.toList());
        return new PageParameters<>(list, BuildQueue.count(), page, perPage);
    }

    @POST
    @Transactional
    @Consumes(MediaType.TEXT_PLAIN)
    public void queueBuild(String buildName) {
        List<BuildQueue> existing = (List<BuildQueue>) entityManager.createQuery(
                "select b from StoredArtifactBuild a inner join BuildQueue b on b.mavenArtifact=a.mavenArtifact where a.buildIdentifier.dependencyBuildName=:b")
                .setParameter("b", buildName).getResultList();
        if (!existing.isEmpty()) {
            existing.get(0).priority = true;
            existing.get(0).rebuild = true;
            return;
        }

        Optional<StoredArtifactBuild> n = StoredArtifactBuild.find("buildIdentifier.dependencyBuildName", buildName)
                .firstResultOptional();
        if (!n.isPresent()) {
            throw new NotFoundException();
        }
        BuildQueue b = new BuildQueue();
        b.priority = true;
        b.rebuild = true;
        b.mavenArtifact = n.get().mavenArtifact;
        b.persistAndFlush();
    }

    @POST
    @Transactional
    @Consumes(MediaType.TEXT_PLAIN)
    @Path("add")
    public void addBuild(String gav) {
        MavenArtifact mavenArtifact = MavenArtifact.forGav(gav);
        MavenArtifactLabel.getOrCreate(mavenArtifact, "From ArtifactEntry");
        BuildQueue.create(mavenArtifact, true);
    }
}

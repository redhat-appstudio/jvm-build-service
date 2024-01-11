package com.redhat.hacbs.management.resources;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;

import com.redhat.hacbs.management.dto.BuildDTO;
import com.redhat.hacbs.management.dto.BuildListDTO;
import com.redhat.hacbs.management.dto.PageParameters;
import com.redhat.hacbs.management.model.StoredDependencyBuild;

import io.quarkus.panache.common.Parameters;

@Path("/builds/history")
public class BuildHistoryResource extends BuildLogs {

    @Inject
    EntityManager entityManager;

    @GET
    public PageParameters<BuildListDTO> all(@QueryParam("page") int page, @QueryParam("perPage") int perPage,
            @QueryParam("state") String state, @QueryParam("gav") String gav) {
        if (perPage <= 0) {
            perPage = 20;
        }

        Parameters parameters = new Parameters();
        StringBuilder query = new StringBuilder();
        if (state != null && !state.isEmpty()) {
            if (Objects.equals(state, "contaminated")) {
                query.append(" WHERE s.contaminated");
            } else if (Objects.equals(state, "complete")) {
                query.append(" WHERE s.succeeded");
            } else if (Objects.equals(state, "failed")) {
                query.append(" WHERE NOT s.succeeded");
            }
        }
        //TODO: this can only find passing builds
        if (gav != null && !gav.isEmpty()) {
            if (!query.isEmpty()) {
                query.append(" and ");
            } else {
                query.append(" WHERE ");
            }
            var parts = gav.split(":");
            if (parts.length == 1) {
                query.append(
                        " s.buildIdentifier in (select a.buildIdentifier from StoredArtifactBuild a inner join a.mavenArtifact m where m.version like :gav or m.identifier.group like :gav or m.identifier.artifact like :gav) or s.buildIdentifier.repository.url like :gav");
                parameters.and("gav", "%" + gav + "%");
            } else if (parts.length == 2) {
                query.append(
                        " s.buildIdentifier in (select a.buildIdentifier from StoredArtifactBuild a inner join a.mavenArtifact m where (m.identifier.group like :p1 and m.identifier.artifact like :p2) or (m.identifier.artifact like :p1 and m.version like :p2) )");
                parameters.and("p1", "%" + parts[0] + "%");
                parameters.and("p2", "%" + parts[1] + "%");
            } else if (parts.length == 3) {
                query.append(
                        " s.buildIdentifier in (select a.buildIdentifier from StoredArtifactBuild a inner join a.mavenArtifact m where m.version like :version and m.identifier.group like :group and m.identifier.artifact like :artifact)");
                parameters.and("group", "%" + parts[0] + "%");
                parameters.and("artifact", "%" + parts[1] + "%");
                parameters.and("version", "%" + parts[2] + "%");
            }
        }
        Query q = entityManager
                .createQuery("select s from StoredDependencyBuild s " + query.toString() + " order by s.creationTimestamp desc")
                .setFirstResult(perPage * (page - 1))
                .setMaxResults(perPage);
        for (var p : parameters.map().entrySet()) {
            q.setParameter(p.getKey(), p.getValue());
        }
        List<StoredDependencyBuild> list = q.getResultList();
        List<BuildListDTO> ret = new ArrayList<>();
        for (var build : list) {
            var inQueue = false;
            Long n = (Long) entityManager.createQuery(
                    "select count(a) from StoredArtifactBuild a inner join BuildQueue b on b.mavenArtifact=a.mavenArtifact where a.buildIdentifier=:b")
                    .setParameter("b", build.buildIdentifier).getSingleResult();
            if (n > 0) {
                inQueue = true;
            }
            ret.add(new BuildListDTO(build.id, build.buildIdentifier.dependencyBuildName, build.buildIdentifier.repository.url,
                    build.buildIdentifier.tag, build.succeeded, build.contaminated, inQueue,
                    build.creationTimestamp.toEpochMilli()));
        }

        q = entityManager.createQuery("select count(s) from StoredDependencyBuild s " + query);
        for (var p : parameters.map().entrySet()) {
            q.setParameter(p.getKey(), p.getValue());
        }
        long count = (long) q.getSingleResult();
        return new PageParameters<>(ret, count, page, perPage);
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

    @GET
    @Path("/discovery-logs/{id}")
    public Response logs(@PathParam("id") int id) {
        StoredDependencyBuild attempt = StoredDependencyBuild.findById(id);
        if (attempt == null) {
            throw new NotFoundException();
        }
        URI uri = URI.create(attempt.buildDiscoveryUrl);
        return extractLog(Type.DISCOVERY, uri, attempt.buildIdentifier.dependencyBuildName);
    }
}

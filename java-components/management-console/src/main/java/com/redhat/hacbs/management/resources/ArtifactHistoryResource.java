package com.redhat.hacbs.management.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import com.redhat.hacbs.management.dto.ArtifactListDTO;
import com.redhat.hacbs.management.dto.PageParameters;
import com.redhat.hacbs.management.model.StoredArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.quarkus.panache.common.Parameters;

@Path("/artifacts/history")
public class ArtifactHistoryResource {

    @Inject
    EntityManager entityManager;

    @GET
    public PageParameters<ArtifactListDTO> all(@QueryParam("page") int page, @QueryParam("perPage") int perPage,
            @QueryParam("state") String state, @QueryParam("gav") String gav) {
        if (perPage <= 0) {
            perPage = 20;
        }
        Parameters parameters = new Parameters();
        StringBuilder query = new StringBuilder();
        if (state != null && !state.isEmpty()) {
            if (Objects.equals(state, "missing")) {
                query.append(" WHERE s.state=:state");
                parameters.and("state", ModelConstants.ARTIFACT_BUILD_MISSING);
            } else if (Objects.equals(state, "complete")) {
                query.append(" WHERE s.state=:state");
                parameters.and("state", ModelConstants.ARTIFACT_BUILD_COMPLETE);
            } else if (Objects.equals(state, "failed")) {
                query.append(" WHERE s.state=:state");
                parameters.and("state", ModelConstants.ARTIFACT_BUILD_FAILED);
            }
        }
        if (gav != null && !gav.isEmpty()) {
            if (!query.isEmpty()) {
                query.append(" and ");
            } else {
                query.append(" WHERE ");
            }
            var parts = gav.split(":");
            if (parts.length == 1) {
                query.append(
                        " s.id in (select a.id from StoredArtifactBuild a inner join a.mavenArtifact m where m.version like :gav or m.identifier.group like :gav or m.identifier.artifact like :gav)");
                parameters.and("gav", "%" + gav + "%");
            } else if (parts.length == 2) {
                query.append(
                        " s.id in (select a.id from StoredArtifactBuild a inner join a.mavenArtifact m where (m.identifier.group like :p1 and m.identifier.artifact like :p2) or (m.identifier.artifact like :p1 and m.version like :p2) )");
                parameters.and("p1", "%" + parts[0] + "%");
                parameters.and("p2", "%" + parts[1] + "%");
            } else if (parts.length == 3) {
                query.append(
                        " s.id in (select a.id from StoredArtifactBuild a inner join a.mavenArtifact m where m.version like :version and m.identifier.group like :group and m.identifier.artifact like :artifact)");
                parameters.and("group", "%" + parts[0] + "%");
                parameters.and("artifact", "%" + parts[1] + "%");
                parameters.and("version", "%" + parts[2] + "%");
            }
        }

        TypedQuery<StoredArtifactBuild> q = entityManager
                .createQuery("select s from StoredArtifactBuild s " + query + " order by s"
                        + ".creationTimestamp desc", StoredArtifactBuild.class)
                .setFirstResult(perPage * (page - 1))
                .setMaxResults(perPage);
        for (var p : parameters.map().entrySet()) {
            q.setParameter(p.getKey(), p.getValue());
        }
        List<StoredArtifactBuild> list = q.getResultList();
        List<ArtifactListDTO> ret = new ArrayList<>();
        for (StoredArtifactBuild storedArtifactBuild : list) {
            ret.add(new ArtifactListDTO(storedArtifactBuild.mavenArtifact.gav(), storedArtifactBuild.name,
                    Objects.equals(storedArtifactBuild.state, ModelConstants.ARTIFACT_BUILD_COMPLETE),
                    Objects.equals(storedArtifactBuild.state, ModelConstants.ARTIFACT_BUILD_MISSING),
                    storedArtifactBuild.message));
        }
        TypedQuery<Long> q2 = entityManager.createQuery("select count(s) from StoredArtifactBuild s " + query,
                Long.class);
        for (var p : parameters.map().entrySet()) {
            q2.setParameter(p.getKey(), p.getValue());
        }
        long count = q2.getSingleResult();
        return new PageParameters<>(ret, count, page, perPage);
    }
}

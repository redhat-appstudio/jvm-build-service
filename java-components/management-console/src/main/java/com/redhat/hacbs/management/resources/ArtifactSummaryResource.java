package com.redhat.hacbs.management.resources;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import com.redhat.hacbs.management.dto.ArtifactSummaryDTO;
import com.redhat.hacbs.management.model.StoredArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

@Path("/artifact/summary")
public class ArtifactSummaryResource {

    @Inject
    EntityManager entityManager;

    @GET
    public ArtifactSummaryDTO summary() {
        long missing = StoredArtifactBuild.count("status", ModelConstants.ARTIFACT_BUILD_MISSING);
        long failed = StoredArtifactBuild.count("status", ModelConstants.ARTIFACT_BUILD_FAILED);
        long build = (long) entityManager
                .createQuery("select count(*) from StoredDependencyBuild s inner join s.producedArtifacts").getSingleResult();
        return new ArtifactSummaryDTO(build, missing, failed, build + missing + failed);
    }
}

package com.redhat.hacbs.management.resources;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import com.redhat.hacbs.management.dto.ArtifactSummaryDTO;
import com.redhat.hacbs.management.model.StoredArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

@Path("/artifact/summary")
public class ArtifactSummaryResource {

    @Inject
    EntityManager entityManager;

    @GET
    public ArtifactSummaryDTO summary(@QueryParam("label") String label) {
        if (label == null || label.isBlank()) {
            long missing = StoredArtifactBuild.count("state", ModelConstants.ARTIFACT_BUILD_MISSING);
            long failed = StoredArtifactBuild.count("state", ModelConstants.ARTIFACT_BUILD_FAILED);
            long built = StoredArtifactBuild.count("state", ModelConstants.ARTIFACT_BUILD_COMPLETE);
            return new ArtifactSummaryDTO(built, missing, failed, built + missing + failed);
        } else {

            long missing = (long) entityManager
                    .createQuery(
                            "select count(a) from StoredArtifactBuild a inner join MavenArtifactLabel l on l.artifact=a.mavenArtifact inner join ArtifactLabelName n on l.name=n where n.name=:name and a.state=:state")
                    .setParameter("state", ModelConstants.ARTIFACT_BUILD_MISSING)
                    .setParameter("name", label).getSingleResult();
            long failed = (long) entityManager
                    .createQuery(
                            "select count(a) from StoredArtifactBuild a inner join MavenArtifactLabel l on l.artifact=a.mavenArtifact inner join ArtifactLabelName n on l.name=n where n.name=:name and a.state=:state")
                    .setParameter("state", ModelConstants.ARTIFACT_BUILD_FAILED)
                    .setParameter("name", label).getSingleResult();
            long built = (long) entityManager
                    .createQuery(
                            "select count(a) from StoredArtifactBuild a inner join MavenArtifactLabel l on l.artifact=a.mavenArtifact inner join ArtifactLabelName n on l.name=n where n.name=:name and a.state=:state")
                    .setParameter("state", ModelConstants.ARTIFACT_BUILD_COMPLETE)
                    .setParameter("name", label).getSingleResult();

            return new ArtifactSummaryDTO(built, missing, failed, built + missing + failed);
        }
    }
}

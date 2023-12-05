package com.redhat.hacbs.management.resources;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import com.redhat.hacbs.management.dto.BuildSummaryDTO;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
import com.redhat.hacbs.management.watcher.BuildOrchestrator;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

@Path("/builds/status")
public class BuildSummaryResource {

    @Inject
    BuildOrchestrator orchestrator;

    @Inject
    EntityManager entityManager;

    @GET
    public BuildSummaryDTO all(@QueryParam("label") String label) {
        if (label != null && !label.isBlank()) {
            long all = (Long) entityManager.createQuery(
                    "select count(*) from StoredDependencyBuild s where s.id in (select b.id from StoredDependencyBuild  b inner join  b.producedArtifacts a inner join MavenArtifactLabel l on l.artifact = a inner join ArtifactLabelName aln on l.name=aln where aln.name=:name)")
                    .setParameter("name", label)
                    .getSingleResult();
            long successful = (Long) entityManager.createQuery(
                    "select count(*) from StoredDependencyBuild s where s.id in (select b.id from StoredDependencyBuild  b inner join  b.producedArtifacts a inner join MavenArtifactLabel l on l.artifact = a inner join ArtifactLabelName aln on l.name=aln where aln.name=:name) and succeeded=true")
                    .setParameter("name", label)
                    .getSingleResult();
            long contaminated = (Long) entityManager.createQuery(
                    "select count(*) from StoredDependencyBuild s where s.id in (select b.id from StoredDependencyBuild  b inner join  b.producedArtifacts a inner join MavenArtifactLabel l on l.artifact = a inner join ArtifactLabelName aln on l.name=aln where aln.name=:name) and contaminated=true")
                    .setParameter("name", label)
                    .getSingleResult();

            //no produced artifacts for failing builds
            //need to lookup artifacts directly
            long failedArtifacts = (long) entityManager
                    .createQuery(
                            "select count(*) from StoredDependencyBuild b where b.buildIdentifier in (select a.buildIdentifier from StoredArtifactBuild a inner join MavenArtifactLabel l on l.artifact=a.mavenArtifact inner join ArtifactLabelName n on l.name=n where n.name=:name and a.status=:status)")
                    .setParameter("status", ModelConstants.ARTIFACT_BUILD_FAILED)
                    .setParameter("name", label).getSingleResult();
            long failing = all - successful - contaminated + failedArtifacts;
            return new BuildSummaryDTO(all, successful, contaminated, 0, failing);
        } else {
            long all = StoredDependencyBuild.count();
            long successful = StoredDependencyBuild.count("succeeded", true);
            long contaminated = StoredDependencyBuild.count("contaminated", true);
            long failing = all - successful - contaminated;
            int running = orchestrator.getRunningBuilds();
            return new BuildSummaryDTO(all + running, successful, contaminated, running, failing);
        }
    }

}

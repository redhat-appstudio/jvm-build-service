package com.redhat.hacbs.management.importer;

import java.time.Instant;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.redhat.hacbs.management.model.BuildIdentifier;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.StoredArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;
import com.redhat.hacbs.resources.util.ResourceNameUtils;

import io.quarkus.logging.Log;
import io.quarkus.panache.common.Parameters;

@ApplicationScoped
public class ArtifactBuildImporter {

    @Transactional
    public void doImport(ArtifactBuild artifactBuild) {
        if (artifactBuild.getStatus() == null || artifactBuild.getStatus().getState() == null) {
            return;
        }
        Log.infof("importing artifact build %s for %s", artifactBuild.getMetadata().getName(),
                artifactBuild.getSpec().getGav());
        boolean complete = Objects.equals(artifactBuild.getStatus().getState(), ModelConstants.ARTIFACT_BUILD_COMPLETE);
        boolean failed = Objects.equals(artifactBuild.getStatus().getState(), ModelConstants.ARTIFACT_BUILD_FAILED);
        boolean missing = Objects.equals(artifactBuild.getStatus().getState(),
                ModelConstants.ARTIFACT_BUILD_MISSING);
        if (!complete &&
                !failed &&
                !missing) {
            //don't import in-progress builds
            return;
        }
        MavenArtifact artifact = MavenArtifact.forGav(artifactBuild.getSpec().getGav());
        StoredArtifactBuild storedBuild = StoredArtifactBuild.find("mavenArtifact=:artifact and uid=:uid",
                Parameters.with("artifact", artifact).and("uid", artifactBuild.getMetadata().getUid())).firstResult();
        if (storedBuild == null) {
            storedBuild = new StoredArtifactBuild();
            storedBuild.uid = artifactBuild.getMetadata().getUid();
            storedBuild.mavenArtifact = artifact;
            storedBuild.creationTimestamp = Instant.parse(artifactBuild.getMetadata().getCreationTimestamp());
        }
        storedBuild.status = artifactBuild.getStatus().getState();
        storedBuild.message = artifactBuild.getStatus().getMessage();
        if (artifactBuild.getStatus().getScm() != null &&
                artifactBuild.getStatus().getScm().getScmURL() != null) {
            String url = artifactBuild.getStatus().getScm().getScmURL();
            String tag = artifactBuild.getStatus().getScm().getTag();
            String hash = artifactBuild.getStatus().getScm().getCommitHash();
            String path = artifactBuild.getStatus().getScm().getPath();
            storedBuild.buildIdentifier = BuildIdentifier.findORCreate(url, tag, hash, path,
                    ResourceNameUtils.dependencyBuildName(url, tag, path));
        }
        storedBuild.persist();

    }
}

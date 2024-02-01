package com.redhat.hacbs.management.resources;

import java.util.Map;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.management.model.StoredArtifactBuild;
import com.redhat.hacbs.management.model.StoredDependencyBuild;

@Path("admin")
@Transactional
public class AdminResource {

    @POST
    @Path("rebuild-all")
    public void rebuildAll() {
        for (StoredDependencyBuild sb : StoredDependencyBuild.<StoredDependencyBuild> listAll()) {
            StoredArtifactBuild sa = StoredArtifactBuild.find("buildIdentifier", sb.buildIdentifier).firstResult();
            BuildQueue.rebuild(sa.mavenArtifact, false, Map.of());
        }
    }

    @POST
    @Path("rebuild-failed")
    public void rebuildFailed() {
        for (StoredDependencyBuild sb : StoredDependencyBuild.<StoredDependencyBuild> list("succeeded", false)) {
            StoredArtifactBuild sa = StoredArtifactBuild.find("buildIdentifier", sb.buildIdentifier).firstResult();
            BuildQueue.rebuild(sa.mavenArtifact, false, Map.of());
        }
    }
}

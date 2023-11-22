package com.redhat.hacbs.management.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import com.redhat.hacbs.management.dto.DeploymentDTO;
import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.ContainerImage;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
import com.redhat.hacbs.management.watcher.DeploymentWatcher;

@Path("/deployment")
public class DeploymentResource {

    @Inject
    DeploymentWatcher deploymentWatcher;

    @Inject
    EntityManager entityManager;

    @GET
    public List<DeploymentDTO> getDeployments() {
        List<DeploymentDTO> ret = new ArrayList<>();
        for (var i : deploymentWatcher.getDeployments().entrySet()) {
            DeploymentDTO info = new DeploymentDTO();
            info.namespace = i.getKey().namespace();
            info.name = i.getKey().name();
            for (var image : i.getValue()) {
                var existing = ContainerImage.findImage(image);
                if (existing == null) {
                    info.analysisComplete = false;
                } else {
                    List<DeploymentDTO.Dependency> depList = new ArrayList<>();
                    info.analysisComplete = existing.analysisComplete;

                    //TODO: this is slow as hell
                    for (var dep : existing.imageDependencies) {
                        Map<String, String> attributes = new HashMap<>();
                        if (dep.attributes != null) {
                            for (var s : dep.attributes.split(";")) {
                                var parts = s.split("=");
                                attributes.put(parts[0], parts[1]);
                            }
                        }
                        Long buildId = null;
                        boolean buildSuccess = false;
                        if (dep.buildId == null) {
                            try {
                                StoredDependencyBuild db = (StoredDependencyBuild) entityManager.createQuery(
                                        "select b from StoredArtifactBuild s inner join StoredDependencyBuild b on b.buildIdentifier=s.buildIdentifier where s.mavenArtifact = :artifact order by b.creationTime desc")
                                        .setParameter("artifact", dep.mavenArtifact)
                                        .setMaxResults(1)
                                        .getSingleResult();
                                buildId = db.id;
                                buildSuccess = db.succeeded;
                            } catch (NoResultException ignore) {
                            }
                        } else {
                            BuildAttempt db = BuildAttempt.find("buildId", dep.buildId).singleResult();
                            if (db != null) {
                                buildId = db.dependencyBuild.id;
                                buildSuccess = db.dependencyBuild.succeeded;
                            }
                        }
                        var inQueue = false;
                        Long n = (Long) entityManager.createQuery(
                                "select count(*) from BuildQueue b where b.mavenArtifact=:artifact")
                                .setParameter("artifact", dep.mavenArtifact)
                                .getSingleResult();
                        if (n > 0) {
                            inQueue = true;
                        }
                        DeploymentDTO.Dependency d = new DeploymentDTO.Dependency(dep.mavenArtifact.gav(), dep.source,
                                buildId, inQueue, buildSuccess, attributes);
                        depList.add(d);
                    }
                    Collections.sort(depList);
                    info.images.add(new DeploymentDTO.Image(image, existing.analysisComplete, depList));
                }
            }
            ret.add(info);
        }
        return ret;
    }

}

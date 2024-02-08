package com.redhat.hacbs.management.resources;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import com.redhat.hacbs.management.dto.DeploymentDTO;
import com.redhat.hacbs.management.dto.ImageDTO;
import com.redhat.hacbs.management.model.ContainerImage;
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
            for (var image : i.getValue().getImages()) {
                var existing = ContainerImage.findImage(image);
                if (existing == null) {
                    info.analysisComplete = false;
                } else {
                    info.analysisComplete = existing.analysisComplete;
                    if (existing.dependencySet.dependencies.isEmpty()) {
                        continue;
                    }
                    info.images.add(new ImageDTO(existing.repository.repository, existing.tag, existing.digest,
                            existing.analysisComplete, existing.dependencySet.id));
                }
            }
            if (!info.images.isEmpty()) {
                ret.add(info);
            }
        }
        return ret;
    }

}

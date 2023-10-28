package com.redhat.hacbs.management.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import com.redhat.hacbs.management.dto.DeploymentDTO;
import com.redhat.hacbs.management.model.ContainerImage;
import com.redhat.hacbs.management.watcher.DeploymentWatcher;

@Path("/deployment")
public class DeploymentResource {

    @Inject
    DeploymentWatcher deploymentWatcher;

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
                    for (var dep : existing.imageDependencies) {
                        Map<String, String> attributes = new HashMap<>();
                        if (dep.attributes != null) {
                            for (var s : dep.attributes.split(";")) {
                                var parts = s.split("=");
                                attributes.put(parts[0], parts[1]);
                            }
                        }
                        DeploymentDTO.Dependency d = new DeploymentDTO.Dependency(dep.mavenArtifact.gav(), dep.source,
                                dep.buildId, attributes);
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

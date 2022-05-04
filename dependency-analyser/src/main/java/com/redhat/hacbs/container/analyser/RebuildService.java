package com.redhat.hacbs.container.analyser;

import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildRequest;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildRequestStatus;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.logging.Log;

@Singleton
public class RebuildService {

    @Inject
    KubernetesClient kubernetesClient;

    public void rebuild(Set<String> gavs) {
        Log.infof("Identified Community Dependencies: %s", gavs);
        //know we know which community dependencies went into the build

        //now use the kube client to stick it into a CR to signify that these dependencies should be built
        for (var gav : gavs) {
            try {
                StringBuilder newName = new StringBuilder();
                //turn the gav into a unique name that satisfies the name rules
                //we could just hash it, but these names are easier for humans
                for (var i : gav.toCharArray()) {
                    if (Character.isAlphabetic(i) || Character.isDigit(i) || i == '.') {
                        newName.append(Character.toLowerCase(i));
                    } else {
                        newName.append(".br.");
                    }
                }
                ArtifactBuildRequest item = new ArtifactBuildRequest();
                ObjectMeta objectMeta = new ObjectMeta();
                objectMeta.setName(newName.toString());
                objectMeta.setAdditionalProperty("gav", gav);
                item.setMetadata(objectMeta);
                item.getSpec().setGav(gav);
                item.getStatus().setState(ArtifactBuildRequestStatus.State.NEW);
                item.setKind(ArtifactBuildRequest.class.getSimpleName());
                kubernetesClient.resources(ArtifactBuildRequest.class).create(item);
            } catch (KubernetesClientException e) {
                if (!e.getStatus().getReason().equals("AlreadyExists")) {
                    throw e;
                }
            }
        }
    }
}

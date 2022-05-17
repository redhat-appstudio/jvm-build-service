package com.redhat.hacbs.container.analyser;

import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildRequest;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.logging.Log;
import io.quarkus.runtime.util.HashUtil;

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
                //generate names based on the artifact name + version, and part of a hash
                //we only use the first 8 characters from the hash to make the name small
                String hash = HashUtil.sha1(gav).substring(0, 8);
                StringBuilder newName = new StringBuilder();
                boolean lastDot = false;
                String namePart = gav.substring(gav.indexOf(':') + 1);
                for (var i : namePart.toCharArray()) {
                    if (Character.isAlphabetic(i) || Character.isDigit(i)) {
                        newName.append(Character.toLowerCase(i));
                        lastDot = false;
                    } else {
                        if (!lastDot) {
                            newName.append('.');
                        }
                        lastDot = true;
                    }
                }
                newName.append("-");
                newName.append(hash);
                ArtifactBuildRequest item = new ArtifactBuildRequest();
                ObjectMeta objectMeta = new ObjectMeta();
                objectMeta.setName(newName.toString());
                objectMeta.setAdditionalProperty("gav", gav);
                item.setMetadata(objectMeta);
                item.getSpec().setGav(gav);
                item.setKind(ArtifactBuildRequest.class.getSimpleName());
                kubernetesClient.resources(ArtifactBuildRequest.class).create(item);
            } catch (KubernetesClientException e) {
                Status status = e.getStatus();
                if (status == null || status.getReason() == null || !status.getReason().equals("AlreadyExists")) {
                    throw e;
                }
            }
        }
    }
}

package com.redhat.hacbs.container.analyser;

import java.util.Set;
import java.util.TreeSet;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.util.ResourceNameUtils;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.logging.Log;

@Singleton
public class RebuildService {

    @Inject
    KubernetesClient kubernetesClient;

    public void rebuild(Set<String> gavs) {
        Log.infof("Identified %s Community Dependencies: %s", gavs.size(), new TreeSet<>(gavs));
        //know we know which community dependencies went into the build

        //now use the kube client to stick it into a CR to signify that these dependencies should be built
        for (var gav : gavs) {
            try {
                //generate names based on the artifact name + version, and part of a hash
                //we only use the first 8 characters from the hash to make the name small

                ArtifactBuild item = new ArtifactBuild();
                ObjectMeta objectMeta = new ObjectMeta();
                objectMeta.setName(ResourceNameUtils.nameFromGav(gav));
                objectMeta.setAdditionalProperty("gav", gav);
                item.setMetadata(objectMeta);
                item.getSpec().setGav(gav);
                item.setKind(ArtifactBuild.class.getSimpleName());
                kubernetesClient.resources(ArtifactBuild.class).create(item);
            } catch (KubernetesClientException e) {
                Status status = e.getStatus();
                if (status == null || status.getReason() == null || !status.getReason().equals("AlreadyExists")) {
                    throw e;
                }
            }
        }
    }
}

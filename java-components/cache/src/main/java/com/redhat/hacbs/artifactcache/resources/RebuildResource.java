package com.redhat.hacbs.artifactcache.resources;

import javax.inject.Singleton;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.util.ResourceNameUtils;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.runtime.Startup;
import io.smallrye.common.annotation.Blocking;

@Path("/v1/rebuild")
@Blocking
@Singleton
@Startup
public class RebuildResource {

    final KubernetesClient kubernetesClient;

    public RebuildResource(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    @PUT
    public void rebuild(String toRebuild) throws Exception {

        //now use the kube client to stick it into a CR to signify that these dependencies should be built
        for (var gav : toRebuild.split(",")) {
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

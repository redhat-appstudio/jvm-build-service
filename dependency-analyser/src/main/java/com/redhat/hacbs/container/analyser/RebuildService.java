package com.redhat.hacbs.container.analyser;

import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildRequest;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.quarkus.logging.Log;

@Singleton
public class RebuildService {

    @Inject
    KubernetesClient kubernetesClient;

    public void rebuild(Set<String> gavs) {
        Log.infof("Identified Community Dependencies: %s", gavs);
        //know we know which community dependencies went into the build

        //now use the kube client to stick it into a CR to signify that these dependencies should be built
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> resources = kubernetesClient
                .genericKubernetesResources(ResourceDefinitionContext.fromResourceType(ArtifactBuildRequest.class));
        for (var gav : gavs) {
            GenericKubernetesResource item = new GenericKubernetesResource();
            item.setAdditionalProperty("name", gav);
            resources.create(item);
        }
    }
}

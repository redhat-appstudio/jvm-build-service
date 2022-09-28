package com.redhat.hacbs.artifactcache.artifactwatch;

import java.util.Base64;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.redhat.hacbs.artifactcache.bloom.BloomFilter;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;

@ApplicationScoped
public class RebuiltArtifacts {

    @Inject
    KubernetesClient client;

    private volatile byte[] filter;

    @PostConstruct
    void setup() {
        client.configMaps().inNamespace(client.getNamespace()).withName("jvm-build-service-filter")
                .inform(new ResourceEventHandler<ConfigMap>() {
                    @Override
                    public void onAdd(ConfigMap obj) {
                        filter = Base64.getDecoder().decode(obj.getBinaryData().get("filter"));

                    }

                    @Override
                    public void onUpdate(ConfigMap oldObj, ConfigMap newObj) {
                        filter = Base64.getDecoder().decode(newObj.getBinaryData().get("filter"));
                    }

                    @Override
                    public void onDelete(ConfigMap obj, boolean deletedFinalStateUnknown) {

                    }
                });
    }

    public boolean isPossiblyRebuilt(String gav) {
        return BloomFilter.isPossible(filter, gav);
    }
}

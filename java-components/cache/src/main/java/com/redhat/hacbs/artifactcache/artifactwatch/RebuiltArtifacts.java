package com.redhat.hacbs.artifactcache.artifactwatch;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;

@ApplicationScoped
public class RebuiltArtifacts {

    @Inject
    KubernetesClient client;

    private final Set<String> gavs = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @PostConstruct
    void setup() {
        client.resources(ArtifactBuild.class).inform().addEventHandler(new ResourceEventHandler<ArtifactBuild>() {
            @Override
            public void onAdd(ArtifactBuild artifactBuild) {
                gavs.add(artifactBuild.getSpec().getGav());
            }

            @Override
            public void onUpdate(ArtifactBuild artifactBuild, ArtifactBuild t1) {
                gavs.add(artifactBuild.getSpec().getGav());

            }

            @Override
            public void onDelete(ArtifactBuild artifactBuild, boolean b) {

            }
        });
    }

    public Set<String> getGavs() {
        return Collections.unmodifiableSet(gavs);
    }
}

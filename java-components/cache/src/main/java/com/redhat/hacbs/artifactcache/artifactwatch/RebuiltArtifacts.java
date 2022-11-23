package com.redhat.hacbs.artifactcache.artifactwatch;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifact;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.logging.Log;

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
                Log.infof("Adding new rebuild ArtifactBuild %s", artifactBuild.getSpec().getGav());
                gavs.add(artifactBuild.getSpec().getGav());
            }

            @Override
            public void onUpdate(ArtifactBuild old, ArtifactBuild newObj) {
                Log.infof("Adding modified rebuild ArtifactBuild %s", newObj.getSpec().getGav());
                gavs.add(newObj.getSpec().getGav());
            }

            @Override
            public void onDelete(ArtifactBuild artifactBuild, boolean b) {
                gavs.remove(artifactBuild.getSpec().getGav());
            }
        });
        client.resources(RebuiltArtifact.class).inform().addEventHandler(new ResourceEventHandler<RebuiltArtifact>() {
            @Override
            public void onAdd(RebuiltArtifact artifactBuild) {
                Log.infof("Adding new RebuiltArtifact %s", artifactBuild.getSpec().getGav());
                gavs.add(artifactBuild.getSpec().getGav());
            }

            @Override
            public void onUpdate(RebuiltArtifact old, RebuiltArtifact newObj) {
                Log.infof("Adding updated RebuiltArtifact %s", newObj.getSpec().getGav());
                gavs.add(newObj.getSpec().getGav());
            }

            @Override
            public void onDelete(RebuiltArtifact artifactBuild, boolean b) {
                gavs.remove(artifactBuild.getSpec().getGav());
            }
        });
    }

    public boolean isPossiblyRebuilt(String gav) {
        return gavs.contains(gav);
    }
}

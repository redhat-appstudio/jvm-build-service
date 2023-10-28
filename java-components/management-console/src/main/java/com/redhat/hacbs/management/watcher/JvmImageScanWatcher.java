package com.redhat.hacbs.management.watcher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.management.model.ContainerImage;
import com.redhat.hacbs.management.model.ImageDependency;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.resources.model.v1alpha1.JvmImageScan;
import com.redhat.hacbs.resources.model.v1alpha1.jvmimagescanstatus.Results;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ExecutorRecorder;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;

@Startup
@ApplicationScoped
public class JvmImageScanWatcher {

    @Inject
    KubernetesClient client;

    @PostConstruct
    public void setup() {
        if ((LaunchMode.current() == LaunchMode.TEST
                && !Objects.equals(System.getProperty(Config.KUBERNETES_NAMESPACE_SYSTEM_PROPERTY), "test"))) {
            //don't start in tests, as kube might not be present
            Log.warnf("Kubernetes client disabled so unable to initiate image scan importer");
            return;
        }
        client.resources(JvmImageScan.class).inAnyNamespace().inform(new ResourceEventHandler<JvmImageScan>() {
            @Override
            public void onAdd(JvmImageScan obj) {
                ExecutorRecorder.getCurrent().execute(() -> handleImage(obj));
            }

            @Override
            public void onUpdate(JvmImageScan oldObj, JvmImageScan newObj) {
                onAdd(newObj);
            }

            @Override
            public void onDelete(JvmImageScan obj, boolean deletedFinalStateUnknown) {

            }
        });

    }

    @Transactional
    void handleImage(JvmImageScan resource) {
        var image = resource.getSpec().getImage();
        if (!image.contains("@")) {
            Log.errorf("image %s has no digest, not saving scan result", image);
            client.resource(resource).delete();
            return;
        }
        Log.infof("Processing image scan %s", resource.getMetadata().getName());
        if (resource.getStatus() == null) {
            return;
        }
        if (Objects.equals(resource.getStatus().getState(), "JvmImageScanFailed")) {

            ContainerImage containerImage = ContainerImage.getOrCreate(image);
            containerImage.analysisComplete = true;
            containerImage.analysisFailed = true;
            containerImage.persist();
            return;
        }
        if (!Objects.equals(resource.getStatus().getState(), "JvmImageScanComplete")) {
            return;
        }
        ContainerImage containerImage = ContainerImage.getOrCreate(image);
        if (containerImage.analysisComplete) {
            return;
        }
        Map<String, ImageDependency> existing = new HashMap<>();
        for (var i : containerImage.imageDependencies) {
            existing.put(i.mavenArtifact.gav(), i);
        }
        List<Results> results = resource.getStatus().getResults();
        if (results != null) {
            for (var i : results) {
                ImageDependency id;
                if (existing.containsKey(i.getGav())) {
                    id = existing.get(i.getGav());
                } else {
                    id = new ImageDependency();
                    id.image = containerImage;
                    id.mavenArtifact = MavenArtifact.forGav(i.getGav());
                }
                id.source = i.getSource();
                if (Objects.equals(id.source, "unknown")) {
                    BuildQueue.create(id.mavenArtifact, true);
                }
                if (i.getAttributes() != null) {
                    id.buildId = i.getAttributes().get("build-id");
                    StringBuilder sb = new StringBuilder();
                    for (var e : i.getAttributes().entrySet()) {
                        if (!sb.isEmpty()) {
                            sb.append(";");
                        }
                        sb.append(e.getKey());
                        sb.append("=");
                        sb.append(e.getValue());
                    }
                    id.attributes = sb.toString();
                }
                id.persist();
            }

        }
        containerImage.analysisComplete = true;
        containerImage.persist();
        client.resource(resource).delete();
    }

}

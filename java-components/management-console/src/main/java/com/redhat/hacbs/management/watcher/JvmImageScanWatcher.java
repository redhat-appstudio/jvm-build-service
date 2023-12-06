package com.redhat.hacbs.management.watcher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;

import com.redhat.hacbs.management.model.*;
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

    @ConfigProperty(name = "image-scan.enabled", defaultValue = "true")
    boolean enabled;

    @PostConstruct
    public void setup() {
        if ((LaunchMode.current() == LaunchMode.TEST
                && !Objects.equals(System.getProperty(Config.KUBERNETES_NAMESPACE_SYSTEM_PROPERTY), "test"))) {
            //don't start in tests, as kube might not be present
            Log.warnf("Kubernetes client disabled so unable to initiate image scan importer");
            return;
        }
        if (!enabled) {
            Log.warnf("image scan importer disabled");
            return;
        }
        client.resources(JvmImageScan.class).inAnyNamespace().inform(new ResourceEventHandler<JvmImageScan>() {
            @Override
            public void onAdd(JvmImageScan obj) {
                ContainerImage image = ensureImageExists(obj);
                if (image != null) {
                    ExecutorRecorder.getCurrent().execute(() -> {
                        for (var i = 0; i < 3; ++i) {
                            try {
                                //TODO: this can be a bit racey, multiple things happening at once can add the same artifact to the DB
                                handleImage(obj, image.id);
                                return;
                            } catch (ConstraintViolationException e) {
                                Log.errorf(e, "Failed to import");
                            }
                        }
                    });
                }
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
    ContainerImage ensureImageExists(JvmImageScan resource) {
        var image = resource.getSpec().getImage();
        if (!image.contains("@")) {
            Log.errorf("image %s has no digest, not saving scan result", image);
            client.resource(resource).delete();
            return null;
        }
        Log.infof("Processing image scan %s", resource.getMetadata().getName());
        if (resource.getStatus() == null) {
            return null;
        }
        if (Objects.equals(resource.getStatus().getState(), "JvmImageScanFailed")) {
            ContainerImage containerImage = ContainerImage.getOrCreate(image);
            containerImage.analysisComplete = true;
            containerImage.analysisFailed = true;
            containerImage.persist();
            return null;
        }
        if (!Objects.equals(resource.getStatus().getState(), "JvmImageScanComplete")) {
            return null;
        }
        return ContainerImage.getOrCreate(image);
    }

    @Transactional
    void handleImage(JvmImageScan resource, long imageId) {
        ContainerImage containerImage = ContainerImage.findById(imageId);
        if (containerImage.analysisComplete) {
            return;
        }
        Map<String, ImageDependency> existing = new HashMap<>();
        if (containerImage.imageDependencies != null) {
            for (var i : containerImage.imageDependencies) {
                existing.put(i.mavenArtifact.gav(), i);
            }
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
                MavenArtifactLabel.getOrCreate(id.mavenArtifact, "From Deployment");
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

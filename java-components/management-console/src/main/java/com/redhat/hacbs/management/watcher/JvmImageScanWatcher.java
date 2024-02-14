package com.redhat.hacbs.management.watcher;

import java.time.Instant;
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

import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.management.model.ContainerImage;
import com.redhat.hacbs.management.model.DependencySet;
import com.redhat.hacbs.management.model.IdentifiedDependency;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.MavenArtifactLabel;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
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

    @ConfigProperty(name = "kube.disabled", defaultValue = "false")
    boolean disabled;

    @PostConstruct
    public void setup() {
        if ((LaunchMode.current() == LaunchMode.TEST
                && !Objects.equals(System.getProperty(Config.KUBERNETES_NAMESPACE_SYSTEM_PROPERTY), "test")) || disabled) {
            //don't start in tests, as kube might not be present
            Log.warnf("Kubernetes client disabled so unable to initiate image scan importer");
            return;
        }
        if (!enabled) {
            Log.warnf("image scan importer disabled");
            return;
        }
        client.resources(JvmImageScan.class).inform(new ResourceEventHandler<JvmImageScan>() {
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
            if (resource.getStatus() != null && resource.getStatus().getDigest() != null
                    && !resource.getStatus().getDigest().isEmpty()) {
                image = image + "@" + resource.getStatus().getDigest();
            } else {
                return null;
            }
        }
        ContainerImage containerImage = ContainerImage.getOrCreate(image,
                Instant.parse(resource.getMetadata().getCreationTimestamp()));
        Log.infof("Processing image scan %s", resource.getMetadata().getName());
        if (resource.getStatus() == null) {
            containerImage.analysisComplete = false;
            containerImage.analysisFailed = false;
            containerImage.persist();
            return null;
        }
        if (Objects.equals(resource.getStatus().getState(), "JvmImageScanFailed")) {
            containerImage.analysisComplete = true;
            containerImage.analysisFailed = true;
            containerImage.persist();
            return null;
        }
        if (!Objects.equals(resource.getStatus().getState(), "JvmImageScanComplete")) {
            containerImage.analysisComplete = false;
            containerImage.analysisFailed = false;
            return null;
        }
        return ContainerImage.getOrCreate(image, Instant.parse(resource.getMetadata().getCreationTimestamp()));
    }

    @Transactional
    void handleImage(JvmImageScan resource, long imageId) {
        ContainerImage containerImage = ContainerImage.findById(imageId);
        if (containerImage.analysisComplete) {
            return;
        }
        Map<String, IdentifiedDependency> existing = new HashMap<>();
        if (containerImage.dependencySet != null && containerImage.dependencySet.dependencies != null) {
            for (var i : containerImage.dependencySet.dependencies) {
                existing.put(i.mavenArtifact.gav(), i);
            }
        }
        if (containerImage.dependencySet == null) {
            containerImage.dependencySet = new DependencySet();
            containerImage.dependencySet.identifier = containerImage.repository.repository + "@" + containerImage.digest;
            containerImage.dependencySet.type = "container-image";
            containerImage.persistAndFlush();
        }
        List<Results> results = resource.getStatus().getResults();
        if (results != null) {
            for (var i : results) {
                IdentifiedDependency id;
                if (existing.containsKey(i.getGav())) {
                    id = existing.get(i.getGav());
                } else {
                    id = new IdentifiedDependency();
                    id.dependencySet = containerImage.dependencySet;
                    id.mavenArtifact = MavenArtifact.forGav(i.getGav());
                }
                MavenArtifactLabel.getOrCreate(id.mavenArtifact, "Image", containerImage.getFullName());
                id.source = i.getSource();
                if (Objects.equals(id.source, "unknown")) {
                    StoredDependencyBuild existingBuild = StoredDependencyBuild.findByArtifact(id.mavenArtifact);
                    if (existingBuild == null) {
                        BuildQueue.create(id.mavenArtifact, true);
                    }
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
        if (LaunchMode.current() == LaunchMode.NORMAL) {
            Log.infof("Marking image resource %s for deletion", resource.getSpec().getImage());
            client.resource(resource).delete();
        }
    }
}

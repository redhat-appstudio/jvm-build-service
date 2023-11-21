package com.redhat.hacbs.management.watcher;

import java.util.Objects;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.management.events.InitialKubeImportCompleteEvent;
import com.redhat.hacbs.management.importer.ArtifactBuildImporter;
import com.redhat.hacbs.management.importer.DependencyBuildImporter;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ExecutorRecorder;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;

@ApplicationScoped
@Startup
public class BuildImportWatcher {

    @Inject
    KubernetesClient client;

    @Inject
    DependencyBuildImporter dependencyBuildImporter;
    @Inject
    ArtifactBuildImporter artifactBuildImporter;

    @ConfigProperty(name = "kube.disabled", defaultValue = "false")
    boolean disabled;

    @Inject
    Event<InitialKubeImportCompleteEvent> initialKubeImportCompleteEvent;

    @PostConstruct
    void setup() {
        if ((LaunchMode.current() == LaunchMode.TEST
                && !Objects.equals(System.getProperty(Config.KUBERNETES_NAMESPACE_SYSTEM_PROPERTY), "test")) || disabled) {
            //don't start in tests, as kube might not be present
            Log.warnf("Kubernetes client disabled so unable to initiate DependencyBuild importer");
            return;
        }
        //need a new thread for this, as it runs all initial events in the main thread
        ExecutorRecorder.getCurrent().execute(new Runnable() {
            @Override
            public void run() {

                client.resources(DependencyBuild.class).inform().addEventHandler(new ResourceEventHandler<DependencyBuild>() {
                    @Override
                    public void onAdd(DependencyBuild build) {
                        try {
                            dependencyBuildImporter.doImport(build);
                        } catch (Exception e) {
                            Log.errorf(e, "Failed to import DependencyBuild %s", build.getMetadata().getName());
                        }
                    }

                    @Override
                    public void onUpdate(DependencyBuild old, DependencyBuild newObj) {
                        try {
                            dependencyBuildImporter.doImport(newObj);
                        } catch (Exception e) {
                            Log.errorf(e, "Failed to import DependencyBuild %s", newObj.getMetadata().getName());
                        }
                    }

                    @Override
                    public void onDelete(DependencyBuild artifactBuild, boolean deletedFinalStateUnknown) {
                    }
                });
                client.resources(ArtifactBuild.class).inform().addEventHandler(new ResourceEventHandler<ArtifactBuild>() {
                    @Override
                    public void onAdd(ArtifactBuild build) {
                        try {
                            artifactBuildImporter.doImport(build);
                        } catch (Exception e) {
                            Log.errorf(e, "Failed to import DependencyBuild %s", build.getMetadata().getName());
                        }
                    }

                    @Override
                    public void onUpdate(ArtifactBuild old, ArtifactBuild newObj) {
                        try {
                            artifactBuildImporter.doImport(newObj);
                        } catch (Exception e) {
                            Log.errorf(e, "Failed to import DependencyBuild %s", newObj.getMetadata().getName());
                        }
                    }

                    @Override
                    public void onDelete(ArtifactBuild artifactBuild, boolean deletedFinalStateUnknown) {
                    }
                });

                initialKubeImportCompleteEvent.fire(new InitialKubeImportCompleteEvent());
            }
        });
    }

}

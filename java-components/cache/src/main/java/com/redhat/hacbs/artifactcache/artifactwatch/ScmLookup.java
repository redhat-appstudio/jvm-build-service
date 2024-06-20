package com.redhat.hacbs.artifactcache.artifactwatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.artifactcache.services.RecipeManager;
import com.redhat.hacbs.resources.model.maven.GAV;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildStatus;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;
import com.redhat.hacbs.resources.model.v1alpha1.artifactbuildstatus.Scm;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;

@ApplicationScoped
@Startup
public class ScmLookup {

    @Inject
    KubernetesClient client;

    @ConfigProperty(name = "kube.disabled", defaultValue = "false")
    boolean disabled;

    final List<Consumer<String>> imageDeletionListeners = Collections.synchronizedList(new ArrayList<>());

    private final Set<String> gavs = Collections.newSetFromMap(new ConcurrentHashMap<>());

    final RecipeManager recipeManager;

    public ScmLookup(RecipeManager recipeManager) {
        this.recipeManager = recipeManager;
    }

    @PostConstruct
    void setup() {

        if (LaunchMode.current() == LaunchMode.TEST || disabled) {
            //don't start in tests, as kube might not be present
            return;
        }
        client.resources(ArtifactBuild.class).inform().addEventHandler(new ResourceEventHandler<ArtifactBuild>() {

            @Override
            public void onAdd(ArtifactBuild newObj) {
                if (newObj.getMetadata().getAnnotations() != null &&
                        newObj.getMetadata().getAnnotations().containsKey(ModelConstants.REBUILD)) {
                    return;
                }
                if (newObj.getStatus() == null) {
                    newObj.setStatus(new ArtifactBuildStatus());
                }
                for (var i = 0; i < 3; ++i) { //retry loop
                    if (newObj.getStatus() == null || newObj.getStatus().getState() == null
                            || Objects.equals(newObj.getStatus().getState(), "")
                            || Objects.equals(newObj.getStatus().getState(), ModelConstants.ARTIFACT_BUILD_NEW)) {
                        Log.infof("updating SCM info for %s", newObj.getMetadata().getName());
                        try {
                            if (newObj.getStatus() == null) {
                                newObj.setStatus(new ArtifactBuildStatus());
                            }
                            try {
                                if (newObj.getMetadata().getAnnotations() != null
                                        && newObj.getMetadata().getAnnotations().containsKey(ModelConstants.REBUILT)
                                        && !newObj.getMetadata().getAnnotations()
                                                .containsKey(ModelConstants.DEPENDENCY_CREATED)) {
                                    //if this is a forced rebuild we always update the SCM info
                                    //there is a good chance there may be a new recipe
                                    recipeManager.forceUpdate();
                                }
                                Scm scm = new Scm();

                                if (newObj.getMetadata().getAnnotations() != null &&
                                        newObj.getMetadata().getAnnotations().containsKey(ModelConstants.DEPENDENCY_CREATED) &&
                                        ModelConstants.ARTIFACT_BUILD_NEW.equals(newObj.getStatus().getState())) {
                                    // If originally created from a DependencyBuild using a custom SCM but we're doing a rebuild
                                    // then don't use the recipe DB for GAV lookup.
                                    scm = newObj.getStatus().getScm();
                                    Log.infof("Tagging artifactBuild for rebuild with URI %s and hash %s ", scm.getScmURL(),
                                            scm.getCommitHash());
                                } else if (newObj.getMetadata().getAnnotations() != null &&
                                        newObj.getMetadata().getAnnotations().containsKey(ModelConstants.DEPENDENCY_CREATED)) {
                                    // If the DependencyBuild was created directly no need to look up the GAV source, instead gather
                                    // from existing dependency.
                                    var depName = newObj.getMetadata().getAnnotations().get(ModelConstants.DEPENDENCY_CREATED);
                                    var resource = client.resources(DependencyBuild.class).withName(depName);
                                    DependencyBuild dependencyBuild = resource.get();
                                    var scmInfo = dependencyBuild.getSpec().getScm();
                                    scm.setScmType("git");
                                    scm.setScmURL(scmInfo.getScmURL());
                                    scm.setCommitHash(scmInfo.getCommitHash());
                                    scm.setPath(scmInfo.getPath());
                                    scm.set_private(scmInfo.get_private());
                                    scm.setTag(scmInfo.getTag());
                                    Log.infof(
                                            "Updating artifactBuild with Dependency %s (state: %s) with GAV %s with URI %s and hash %s ",
                                            depName, dependencyBuild.getStatus().getState(), newObj.getSpec().getGav(),
                                            scm.getScmURL(), scm.getCommitHash());
                                } else {
                                    var result = recipeManager.locator().resolveTagInfo(GAV.parse(newObj.getSpec().getGav()));
                                    scm.setScmType("git");
                                    scm.setScmURL(result.getRepoInfo().getUri());
                                    scm.setCommitHash(result.getHash());
                                    String path = result.getRepoInfo().getPath();
                                    if (path != null && path.startsWith("/")) {
                                        path = path.substring(1);
                                    }
                                    scm.setPath(path);
                                    scm.set_private(result.getRepoInfo().isPrivateRepo());
                                    scm.setTag(result.getTag());
                                    Log.infof("Adding artifactBuild with GAV %s with URI %s and hash %s ",
                                            newObj.getSpec().getGav(), result.getRepoInfo().getUri(), result.getHash());
                                }
                                newObj.getStatus().setState(ModelConstants.ARTIFACT_BUILD_DISCOVERING);
                                newObj.getStatus().setScm(scm);
                                newObj.getStatus().setMessage("");
                            } catch (Exception e) {
                                Log.errorf(e, "Failed to update rebuilt object");
                                newObj.getStatus().setMessage(e.getMessage());
                                newObj.getStatus().setState(ModelConstants.ARTIFACT_BUILD_MISSING);
                                // Not setting status label to missing here but will be handled in artifactbuild.go Reconcile
                                // operator loop that calls updateLabel.
                            }
                            client.resource(newObj).patchStatus();
                            return;
                        } catch (KubernetesClientException e) {
                            if (e.getCode() == 409) {
                                //conflict, we will see a new version soon
                                return;
                            }
                            Log.errorf(e, "Failed to update ArtifactBuild with discovery results");
                        } catch (Exception e) {
                            Log.errorf(e, "Failed to update ArtifactBuild with discovery results");
                        }
                    }
                }
            }

            @Override
            public void onUpdate(ArtifactBuild old, ArtifactBuild newObj) {
                onAdd(newObj);
            }

            @Override
            public void onDelete(ArtifactBuild obj, boolean deletedFinalStateUnknown) {

            }
        });
    }

    public void addImageDeletionListener(Consumer<String> listener) {
        imageDeletionListeners.add(listener);
    }

    public boolean isPossiblyRebuilt(String gav) {
        return gavs.contains(gav);
    }
}

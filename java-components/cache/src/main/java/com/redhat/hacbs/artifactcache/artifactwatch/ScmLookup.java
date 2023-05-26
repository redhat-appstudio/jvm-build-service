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
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ScmInfo;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;

@ApplicationScoped
@Startup
public class ScmLookup {

    public static final String REBUILD = "jvmbuildservice.io/rebuild";
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

                for (var i = 0; i < 3; ++i) { //retry loop
                    if (newObj.getStatus().getState() == null || Objects.equals(newObj.getStatus().getState(), "")
                            || Objects.equals(newObj.getStatus().getState(), ArtifactBuild.NEW)) {
                        try {
                            if (newObj.getMetadata().getAnnotations() != null
                                    && newObj.getMetadata().getAnnotations().containsKey(REBUILD)) {
                                //if this is a forced rebuild we always update the SCM info
                                //there is a good chance there may be a new recipe
                                recipeManager.forceUpdate();
                                newObj.getMetadata().getAnnotations().remove(REBUILD);
                                client.resource(newObj).patch();
                            }
                            var result = recipeManager.locator().resolveTagInfo(GAV.parse(newObj.getSpec().getGav()));
                            ScmInfo scm = new ScmInfo();
                            scm.setScmType("git");
                            scm.setScmURL(result.getRepoInfo().getUri());
                            scm.setCommitHash(result.getHash());
                            scm.setPath(result.getRepoInfo().getPath());
                            scm.setPrivateRepo(result.getRepoInfo().isPrivateRepo());
                            scm.setTag(result.getTag());
                            newObj.getStatus().setScm(scm);
                            newObj.getStatus().setMessage("");
                            newObj.getStatus().setState(ArtifactBuild.DISCOVERING);
                        } catch (Exception e) {
                            newObj.getStatus().setMessage(e.getMessage());
                            newObj.getStatus().setState(ArtifactBuild.MISSING);
                        }
                    }
                    try {
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

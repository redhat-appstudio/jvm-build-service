package com.redhat.hacbs.management.watcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.management.dto.RunningBuildDTO;
import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildSpec;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;
import com.redhat.hacbs.resources.util.ResourceNameUtils;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.logging.Log;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.ExecutorRecorder;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;

/**
 * This class manages the build queue and attempts to kick off queued builds.
 */
@Singleton
@Startup
public class BuildOrchestrator {

    public static final String HOURS_TO_LIVE = "jvmbuildservice.io/hours-to-live";
    final KubernetesClient client;

    final EntityManager entityManager;
    final boolean disabled;

    final int concurrentBuilds;

    volatile int runningBuilds;
    volatile List<RunningBuildDTO> runningBuildList = List.of();

    private final Lock lock = new ReentrantLock();

    @Inject
    public BuildOrchestrator(KubernetesClient client,
            EntityManager entityManager, @ConfigProperty(name = "kube.disabled", defaultValue = "false") boolean disabled,
            @ConfigProperty(name = "concurrent-builds") int concurrentBuilds) {
        this.client = client;
        this.entityManager = entityManager;
        this.disabled = disabled;
        this.concurrentBuilds = concurrentBuilds;
    }

    @PostConstruct
    void setupWatcher() {
        if (isDisabled()) {
            //don't start in tests, as kube might not be present
            Log.warnf("Kubernetes client disabled so unable to initiate build orchestrator");
            return;
        }
        //need a new thread for this, as it runs all initial events in the main thread
        ExecutorRecorder.getCurrent().execute(new Runnable() {
            @Override
            public void run() {

                client.resources(DependencyBuild.class).inform().addEventHandler(new ResourceEventHandler<DependencyBuild>() {
                    @Override
                    public void onAdd(DependencyBuild build) {
                    }

                    @Override
                    public void onUpdate(DependencyBuild old, DependencyBuild newObj) {
                        checkBuildQueueAsync();
                    }

                    @Override
                    public void onDelete(DependencyBuild artifactBuild, boolean deletedFinalStateUnknown) {
                        checkBuildQueueAsync();
                    }
                });
                checkBuildQueue();
            }
        });
    }

    private void checkBuildQueueAsync() {
        ExecutorRecorder.getCurrent().execute(new Runnable() {
            @Override
            public void run() {
                checkBuildQueue();
            }
        });
    }

    boolean isDisabled() {
        return (LaunchMode.current() == LaunchMode.TEST
                && !Objects.equals(System.getProperty(Config.KUBERNETES_NAMESPACE_SYSTEM_PROPERTY), "test")) || disabled;
    }

    @Scheduled(every = "1m")
    void checkBuildQueue() {
        if (!lock.tryLock()) {
            return;
        }
        try {

            Log.infof("running build queue orchestration");
            if (isDisabled()) {
                return;
            }
            cleanupRepeats();
            var dbs = client.resources(DependencyBuild.class).list();
            var abrs = client.resources(ArtifactBuild.class).list();
            int count = 0;
            List<RunningBuildDTO> rbl = new ArrayList<>();
            for (var i : dbs.getItems()) {
                if (i.getStatus() != null && i.getStatus().getState() != null &&
                        (Objects.equals(i.getStatus().getState(), ModelConstants.DEPENDENCY_BUILD_COMPLETE) ||
                                Objects.equals(i.getStatus().getState(), ModelConstants.DEPENDENCY_BUILD_CONTAMINATED) ||
                                Objects.equals(i.getStatus().getState(), ModelConstants.DEPENDENCY_BUILD_FAILED))) {
                    continue;
                }
                count++;
                rbl.add(new RunningBuildDTO(i.getSpec().getScm().getScmURL() + "@" + i.getSpec().getScm().getTag(),
                        i.getStatus() == null ? "" : i.getStatus().getState(),
                        Instant.parse(i.getMetadata().getCreationTimestamp())));
            }
            for (var i : abrs.getItems()) {
                if (i.getStatus() == null ||
                        i.getStatus().getState() == null ||
                        Objects.equals(i.getStatus().getState(), ModelConstants.ARTIFACT_BUILD_NEW) ||
                        Objects.equals(i.getStatus().getState(), ModelConstants.ARTIFACT_BUILD_DISCOVERING) ||
                        Objects.equals(i.getStatus().getState(), "")) {
                    count++; //we also count new ABRs
                    rbl.add(new RunningBuildDTO(i.getSpec().getGav(), i.getStatus() == null ? "" : i.getStatus().getState(),
                            Instant.parse(i.getMetadata().getCreationTimestamp())));
                }
            }
            Log.infof("%s currently running jobs", count);

            while (count < concurrentBuilds) {
                if (!createArtifactBuild()) {
                    break;
                }
                count++;
            }
            runningBuilds = count;
            runningBuildList = rbl;
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    void cleanupRepeats() {
        //clear any stuff from the build queue that is a double up
        List<BuildQueue> toDelete = entityManager.createQuery(
                "select b from StoredArtifactBuild a inner join BuildQueue b on b.mavenArtifact=a.mavenArtifact inner join StoredDependencyBuild s on s.buildIdentifier = a.buildIdentifier where b.rebuild = false")
                .getResultList();
        toDelete.forEach(s -> s.delete());
    }

    @Transactional
    boolean createArtifactBuild() {
        for (;;) {
            BuildQueue bq = BuildQueue.find("priority", Sort.ascending("id"), true).firstResult();
            if (bq == null) {
                bq = BuildQueue.find("priority", Sort.ascending("id"), false).firstResult();
            }
            if (bq == null) {
                return false;
            }
            boolean exists = !entityManager
                    .createQuery(
                            "select s from StoredDependencyBuild s join s.buildAttempts ba join ba.producedArtifacts artifact where artifact=:artifact")
                    .setParameter("artifact", bq.mavenArtifact)
                    .getResultList().isEmpty();
            if (!exists || bq.rebuild) {
                String targetGav = bq.mavenArtifact.gav();
                String name = ResourceNameUtils.nameFromGav(targetGav);
                ArtifactBuild existing = client.resources(ArtifactBuild.class).withName(name).get();
                if (existing == null) {
                    Log.infof("creating artifact from the build queue: %s", targetGav);
                    ArtifactBuild artifactBuild = new ArtifactBuild();
                    artifactBuild.getMetadata().setAnnotations(new HashMap<>());
                    artifactBuild.getMetadata().getAnnotations().put(HOURS_TO_LIVE, "10");
                    artifactBuild.setSpec(new ArtifactBuildSpec());
                    artifactBuild.getMetadata().setName(name);
                    artifactBuild.getSpec().setGav(targetGav);
                    client.resource(artifactBuild).create();
                    bq.delete();
                    return true;
                } else if (bq.rebuild) {
                    if (existing.getMetadata().getAnnotations() == null) {
                        existing.getMetadata().setAnnotations(new HashMap<>());
                    }
                    existing.getMetadata().getAnnotations().put(ModelConstants.REBUILD, "true");
                    client.resource(existing).update();
                }
            }
            bq.delete();
        }
    }

    /**
     * An estimate of the number of running builds, this is slow to count so it may be useful in the UI.
     */
    public int getRunningBuilds() {
        return runningBuilds;
    }

    public List<RunningBuildDTO> getRunningBuildList() {
        return runningBuildList;
    }
}

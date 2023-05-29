package com.redhat.hacbs.container.analyser.dependencies;

import java.util.*;
import java.util.function.UnaryOperator;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;

@Singleton
public class RebuildService {

    @Inject
    Instance<KubernetesClient> client;

    public void rebuild(String taskRunName, Set<String> gavs) {

        Log.infof("Identified %s Community Dependencies: %s", gavs.size(), new TreeSet<>(gavs));

        var client = this.client.get();
        var resources = client.resources(TaskRun.class);
        resources.withName(taskRunName).editStatus(new UnaryOperator<TaskRun>() {
            @Override
            public TaskRun apply(TaskRun taskRun) {
                List<TaskRunResult> results = new ArrayList<>();
                if (taskRun.getStatus().getTaskResults() != null) {
                    results.addAll(taskRun.getStatus().getTaskResults());
                }
                Log.infof("Found community dependencies %s", gavs);
                results.add(new TaskRunResult("JAVA_COMMUNITY_DEPENDENCIES", String.join(",", gavs)));
                taskRun.getStatus().setTaskResults(results);
                return taskRun;
            }
        });
    }
}

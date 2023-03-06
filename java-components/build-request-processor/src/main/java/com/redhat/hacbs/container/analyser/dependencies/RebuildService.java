package com.redhat.hacbs.container.analyser.dependencies;

import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.base.Strings;
import com.redhat.hacbs.classfile.tracker.TrackingData;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;

@Singleton
public class RebuildService {

    @Inject
    Instance<KubernetesClient> client;

    public void rebuild(String taskRunName, Set<TrackingData> trackingData) {
        Map<String, String> gavs = new TreeMap<>();
        for (var i : trackingData) {
            gavs.put(i.getGav(), i.getSource());
        }
        Log.infof("Identified %s Community Dependencies: %s", gavs.size(), gavs);

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
                results.add(new TaskRunResult("JAVA_COMMUNITY_DEPENDENCIES", String.join(",", gavs.keySet())));
                results.add(new TaskRunResult("JAVA_DEPENDENCIES",
                        trackingData.stream()
                                .map(e -> e.gav + "|" + e.source + "|"
                                        + Strings.nullToEmpty(e.getAttributes().get(TrackingData.BUILD_ID)))
                                .collect(Collectors.joining(","))));
                taskRun.getStatus().setTaskResults(results);
                return taskRun;
            }
        });
    }
}

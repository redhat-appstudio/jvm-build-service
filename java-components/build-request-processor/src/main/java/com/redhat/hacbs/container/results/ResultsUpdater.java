package com.redhat.hacbs.container.results;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.hacbs.container.analyser.dependencies.TaskRun;
import com.redhat.hacbs.container.analyser.dependencies.TaskRunResult;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.logging.Log;

@ApplicationScoped
public class ResultsUpdater {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    final int retries;

    final KubernetesClient kubernetesClient;

    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Inject
    public ResultsUpdater(KubernetesClient kubernetesClient,
            @ConfigProperty(name = "kube.retries", defaultValue = "5") int retries) {
        this.kubernetesClient = kubernetesClient;
        this.retries = retries;
    }

    public void updateResults(String taskRun, Map<String, String> newResults) {

        for (int i = 0; i <= retries; ++i) {
            try {
                Resource<TaskRun> taskRunResource = kubernetesClient.resources(TaskRun.class)
                        .withName(taskRun);
                taskRunResource.editStatus(new UnaryOperator<TaskRun>() {
                    @Override
                    public TaskRun apply(TaskRun taskRun) {
                        List<TaskRunResult> resultsList = new ArrayList<>();
                        if (taskRun.getStatus().getTaskResults() != null) {
                            for (var i : taskRun.getStatus().getTaskResults()) {
                                if (!newResults.containsKey(i.getName())) {
                                    resultsList.add(i);
                                }
                            }
                        }
                        for (var e : newResults.entrySet()) {
                            resultsList.add(new TaskRunResult(e.getKey(), e.getValue()));
                        }
                        taskRun.getStatus().setTaskResults(resultsList);
                        return taskRun;
                    }
                });
                return;
            } catch (Exception e) {
                Log.errorf(e, "Failed to update TaskRun %s, attempt %s of %s", taskRun, i, retries);
            }
        }
    }
}

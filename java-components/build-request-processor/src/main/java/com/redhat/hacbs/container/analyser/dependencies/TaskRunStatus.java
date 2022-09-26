package com.redhat.hacbs.container.analyser.dependencies;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskRunStatus {

    private List<TaskRunResult> taskResults;

    public List<TaskRunResult> getTaskResults() {
        return taskResults;
    }

    public void setTaskResults(List<TaskRunResult> taskResults) {
        this.taskResults = taskResults;
    }
}

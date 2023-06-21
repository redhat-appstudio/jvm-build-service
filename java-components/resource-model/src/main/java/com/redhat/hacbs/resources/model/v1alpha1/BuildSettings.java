package com.redhat.hacbs.resources.model.v1alpha1;

public class BuildSettings {
    private String buildRequestMemory;
    private String buildRequestCPU;
    private String taskRequestMemory;
    private String taskRequestCPU;
    private String taskLimitMemory;
    private String taskLimitCPU;

    public String getBuildRequestMemory() {
        return buildRequestMemory;
    }

    public BuildSettings setBuildRequestMemory(String buildRequestMemory) {
        this.buildRequestMemory = buildRequestMemory;
        return this;
    }

    public String getBuildRequestCPU() {
        return buildRequestCPU;
    }

    public BuildSettings setBuildRequestCPU(String buildRequestCPU) {
        this.buildRequestCPU = buildRequestCPU;
        return this;
    }

    public String getTaskRequestMemory() {
        return taskRequestMemory;
    }

    public BuildSettings setTaskRequestMemory(String taskRequestMemory) {
        this.taskRequestMemory = taskRequestMemory;
        return this;
    }

    public String getTaskRequestCPU() {
        return taskRequestCPU;
    }

    public BuildSettings setTaskRequestCPU(String taskRequestCPU) {
        this.taskRequestCPU = taskRequestCPU;
        return this;
    }

    public String getTaskLimitMemory() {
        return taskLimitMemory;
    }

    public BuildSettings setTaskLimitMemory(String taskLimitMemory) {
        this.taskLimitMemory = taskLimitMemory;
        return this;
    }

    public String getTaskLimitCPU() {
        return taskLimitCPU;
    }

    public BuildSettings setTaskLimitCPU(String taskLimitCPU) {
        this.taskLimitCPU = taskLimitCPU;
        return this;
    }
}

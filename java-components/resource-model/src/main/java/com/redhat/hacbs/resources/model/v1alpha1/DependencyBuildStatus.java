package com.redhat.hacbs.resources.model.v1alpha1;

public class DependencyBuildStatus {

    private String state;
    private String[] contaminates;

    public String getState() {
        return state;
    }

    public DependencyBuildStatus setState(String state) {
        this.state = state;
        return this;
    }

    public String[] getContaminates() {
        return contaminates;
    }

    public DependencyBuildStatus setContaminates(String[] contaminates) {
        this.contaminates = contaminates;
        return this;
    }
}

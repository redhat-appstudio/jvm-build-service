package com.redhat.hacbs.resources.model.v1alpha1;

import java.util.List;

public class BuildRecipe {

    private String pipeline;
    private String image;
    private List<String> commandLine;

    private String enforceVersion;

    private List<String> ignoredArtifacts;

    public String getImage() {
        return image;
    }

    public BuildRecipe setImage(String image) {
        this.image = image;
        return this;
    }

    public List<String> getCommandLine() {
        return commandLine;
    }

    public BuildRecipe setCommandLine(List<String> commandLine) {
        this.commandLine = commandLine;
        return this;
    }

    public String getEnforceVersion() {
        return enforceVersion;
    }

    public BuildRecipe setEnforceVersion(String enforceVersion) {
        this.enforceVersion = enforceVersion;
        return this;
    }

    public String getPipeline() {
        return pipeline;
    }

    public BuildRecipe setPipeline(String pipeline) {
        this.pipeline = pipeline;
        return this;
    }

    public List<String> getIgnoredArtifacts() {
        return ignoredArtifacts;
    }

    public BuildRecipe setIgnoredArtifacts(List<String> ignoredArtifacts) {
        this.ignoredArtifacts = ignoredArtifacts;
        return this;
    }
}

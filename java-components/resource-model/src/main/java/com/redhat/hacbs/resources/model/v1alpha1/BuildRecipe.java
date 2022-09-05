package com.redhat.hacbs.resources.model.v1alpha1;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BuildRecipe {

    private String pipeline;
    private String image;
    private List<String> commandLine;

    private String enforceVersion;

    private List<String> ignoredArtifacts;

    boolean maven;

    boolean gradle;

    String javaVersion;

    String toolVersion;

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

    public boolean isMaven() {
        return maven;
    }

    public BuildRecipe setMaven(boolean maven) {
        this.maven = maven;
        return this;
    }

    public boolean isGradle() {
        return gradle;
    }

    public BuildRecipe setGradle(boolean gradle) {
        this.gradle = gradle;
        return this;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public BuildRecipe setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
        return this;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public BuildRecipe setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
        return this;
    }
}

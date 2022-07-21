package com.redhat.hacbs.recipies.build;

import java.util.ArrayList;
import java.util.List;

/**
 * TODO: this should be stored per repo/tag/path, not per artifact
 * otherwise in theory artifacts could have different settings which would result in a non-deterministic outcome
 * This is not a now problem, but something we should address in the mid term.
 */
public class BuildRecipeInfo {

    /**
     * If this is true then the version will be explicitly set before doing the build
     */
    boolean enforceVersion;
    List<String> additionalArgs = new ArrayList<>();
    List<String> ignoredArtifacts = new ArrayList<>();
    String toolVersion;
    String javaHome;

    public List<String> getAdditionalArgs() {
        return additionalArgs;
    }

    public BuildRecipeInfo setAdditionalArgs(List<String> additionalArgs) {
        this.additionalArgs = additionalArgs;
        return this;
    }

    public boolean isEnforceVersion() {
        return enforceVersion;
    }

    public BuildRecipeInfo setEnforceVersion(boolean enforceVersion) {
        this.enforceVersion = enforceVersion;
        return this;
    }

    public List<String> getIgnoredArtifacts() {
        return ignoredArtifacts;
    }

    public BuildRecipeInfo setIgnoredArtifacts(List<String> ignoredArtifacts) {
        this.ignoredArtifacts = ignoredArtifacts;
        return this;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public BuildRecipeInfo setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
        return this;
    }

    public String getJavaHome() {
        return javaHome;
    }

    public BuildRecipeInfo setJavaHome(String javaHome) {
        this.javaHome = javaHome;
        return this;
    }
}

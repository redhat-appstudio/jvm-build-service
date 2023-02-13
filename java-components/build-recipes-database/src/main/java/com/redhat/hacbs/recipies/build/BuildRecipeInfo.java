package com.redhat.hacbs.recipies.build;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * TODO: this should be stored per repo/tag/path, not per artifact
 * otherwise in theory artifacts could have different settings which would result in a non-deterministic outcome
 * This is not a now problem, but something we should address in the mid term.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BuildRecipeInfo {

    /**
     * If this is true then the version will be explicitly set before doing the build
     */
    boolean enforceVersion;
    List<String> additionalArgs = new ArrayList<>();

    /**
     * Parameters that are used instead of the default build command line
     */
    List<String> alternativeArgs = new ArrayList<>();

    /**
     * Additional repositories to use in the rebuild.
     */
    List<String> repositories = new ArrayList<>();
    String toolVersion;
    String javaVersion;

    String preBuildScript;

    boolean disableSubmodules;

    int additionalMemory;

    List<AdditionalDownload> additionalDownloads = new ArrayList<>();

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

    public List<String> getRepositories() {
        return repositories;
    }

    public BuildRecipeInfo setRepositories(List<String> repositories) {
        this.repositories = repositories;
        return this;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public BuildRecipeInfo setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
        return this;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public BuildRecipeInfo setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
        return this;
    }

    public String getPreBuildScript() {
        return preBuildScript;
    }

    public BuildRecipeInfo setPreBuildScript(String preBuildScript) {
        this.preBuildScript = preBuildScript;
        return this;
    }

    public List<AdditionalDownload> getAdditionalDownloads() {
        return additionalDownloads;
    }

    public BuildRecipeInfo setAdditionalDownloads(List<AdditionalDownload> additionalDownloads) {
        this.additionalDownloads = additionalDownloads;
        return this;
    }

    public boolean isDisableSubmodules() {
        return disableSubmodules;
    }

    public BuildRecipeInfo setDisableSubmodules(boolean disableSubmodules) {
        this.disableSubmodules = disableSubmodules;
        return this;
    }

    public List<String> getAlternativeArgs() {
        return alternativeArgs;
    }

    public BuildRecipeInfo setAlternativeArgs(List<String> alternativeArgs) {
        this.alternativeArgs = alternativeArgs;
        return this;
    }

    public int getAdditionalMemory() {
        return additionalMemory;
    }

    public BuildRecipeInfo setAdditionalMemory(int additionalMemory) {
        this.additionalMemory = additionalMemory;
        return this;
    }

    @Override
    public String toString() {
        return "BuildRecipeInfo{" +
                "enforceVersion=" + enforceVersion +
                ", additionalArgs=" + additionalArgs +
                ", alternativeArgs=" + alternativeArgs +
                ", repositories=" + repositories +
                ", toolVersion='" + toolVersion + '\'' +
                ", javaVersion='" + javaVersion + '\'' +
                ", preBuildScript='" + preBuildScript + '\'' +
                ", disableSubmodules=" + disableSubmodules +
                ", additionalMemory=" + additionalMemory +
                ", additionalDownloads=" + additionalDownloads +
                '}';
    }
}

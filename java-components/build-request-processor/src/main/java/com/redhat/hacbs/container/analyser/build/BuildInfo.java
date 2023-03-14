package com.redhat.hacbs.container.analyser.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.redhat.hacbs.container.analyser.location.VersionRange;
import com.redhat.hacbs.recipies.build.AdditionalDownload;

public class BuildInfo {

    public static final String JDK = "jdk";
    public static final String MAVEN = "maven";
    public static final String GRADLE = "gradle";
    public static final String SBT = "sbt";
    public static final String ANT = "ant";
    /**
     * Possible build tools, including the JDK. This is represented
     */
    Map<String, VersionRange> tools = new HashMap<>();
    /**
     * List of commands to try. Normally there is only one, but if there is both maven and gradle
     * present then we might try to invoke both.
     */
    List<List<String>> invocations = new ArrayList<>();

    /**
     * Additional repositories to use in the rebuild.
     */
    List<String> repositories = new ArrayList<>();

    String enforceVersion;

    public Map<String, VersionRange> getTools() {
        return tools;
    }

    /**
     * Version of the build tool.
     */
    String toolVersion;

    long commitTime;

    String preBuildScript;

    String postBuildScript;

    List<AdditionalDownload> additionalDownloads = new ArrayList<>();
    boolean disableSubmodules;
    int additionalMemory;

    boolean requiresInternet;

    public BuildInfo setTools(Map<String, VersionRange> tools) {
        this.tools = tools;
        return this;
    }

    public List<List<String>> getInvocations() {
        return invocations;
    }

    public BuildInfo setInvocations(List<List<String>> invocations) {
        this.invocations = invocations;
        return this;
    }

    public String getPreBuildScript() {
        return preBuildScript;
    }

    public BuildInfo setPreBuildScript(String preBuildScript) {
        this.preBuildScript = preBuildScript;
        return this;
    }

    public String getPostBuildScript() {
        return postBuildScript;
    }

    public BuildInfo setPostBuildScript(String postBuildScript) {
        this.postBuildScript = postBuildScript;
        return this;
    }

    public String getEnforceVersion() {
        return enforceVersion;
    }

    public BuildInfo setEnforceVersion(String enforceVersion) {
        this.enforceVersion = enforceVersion;
        return this;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public BuildInfo setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
        return this;
    }

    public long getCommitTime() {
        return commitTime;
    }

    public BuildInfo setCommitTime(long commitTime) {
        this.commitTime = commitTime;
        return this;
    }

    public List<AdditionalDownload> getAdditionalDownloads() {
        return additionalDownloads;
    }

    public BuildInfo setAdditionalDownloads(List<AdditionalDownload> additionalDownloads) {
        this.additionalDownloads = additionalDownloads;
        return this;
    }

    public boolean isDisableSubmodules() {
        return disableSubmodules;
    }

    public BuildInfo setDisableSubmodules(boolean disableSubmodules) {
        this.disableSubmodules = disableSubmodules;
        return this;
    }

    public int getAdditionalMemory() {
        return additionalMemory;
    }

    public BuildInfo setAdditionalMemory(int additionalMemory) {
        this.additionalMemory = additionalMemory;
        return this;
    }

    public List<String> getRepositories() {
        return repositories;
    }

    public BuildInfo setRepositories(List<String> repositories) {
        this.repositories = repositories;
        return this;
    }

    public boolean isRequiresInternet() {
        return requiresInternet;
    }

    public BuildInfo setRequiresInternet(boolean requiresInternet) {
        this.requiresInternet = requiresInternet;
        return this;
    }
}

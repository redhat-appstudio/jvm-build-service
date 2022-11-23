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
    /**
     * Possible build tools, including the JDK. This is represented
     */
    Map<String, VersionRange> tools = new HashMap<>();
    /**
     * List of commands to try. Normally there is only one, but if there is both maven and gradle
     * present then we might try to invoke both.
     */
    List<List<String>> invocations = new ArrayList<>();

    String enforceVersion;

    public Map<String, VersionRange> getTools() {
        return tools;
    }

    /**
     * Version of the build tool.
     */
    String toolVersion;

    /**
     * Java version.
     */
    String javaVersion;

    long commitTime;

    String preBuildScript;

    List<AdditionalDownload> additionalDownloads = new ArrayList<>();

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

    public String getJavaVersion() {
        return javaVersion;
    }

    public BuildInfo setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
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
}

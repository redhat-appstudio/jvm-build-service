package com.redhat.hacbs.container.analyser.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.redhat.hacbs.container.analyser.location.VersionRange;

public class BuildInfo {

    public static final String JDK = "jdk";
    public static final String MAVEN = "mvn";
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

    List<String> ignoredArtifacts = new ArrayList<>();

    public Map<String, VersionRange> getTools() {
        return tools;
    }

    /**
     * Version of the build tool.
     */
    String toolVersion;

    /**
     * Location of Java home ({@code JAVA_HOME}).
     */
    String javaHome;

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

    public String getEnforceVersion() {
        return enforceVersion;
    }

    public BuildInfo setEnforceVersion(String enforceVersion) {
        this.enforceVersion = enforceVersion;
        return this;
    }

    public List<String> getIgnoredArtifacts() {
        return ignoredArtifacts;
    }

    public BuildInfo setIgnoredArtifacts(List<String> ignoredArtifacts) {
        this.ignoredArtifacts = ignoredArtifacts;
        return this;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public BuildInfo setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
        return this;
    }

    public String getJavaHome() {
        return javaHome;
    }

    public BuildInfo setJavaHome(String javaHome) {
        this.javaHome = javaHome;
        return this;
    }
}

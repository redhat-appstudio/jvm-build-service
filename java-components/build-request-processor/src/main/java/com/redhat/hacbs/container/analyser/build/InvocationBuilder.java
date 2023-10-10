package com.redhat.hacbs.container.analyser.build;

import com.redhat.hacbs.recipies.build.BuildRecipeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contains the logic around merging build information and discovery results
 */
public class InvocationBuilder {

    private final BuildRecipeInfo buildRecipeInfo;
    private final Map<String, List<String>> availableTools;
    private final String version;

    private final Map<String, String> discoveredToolVersions = new HashMap<>();

    private final Map<String, Set<List<String>>> toolInvocations = new HashMap<>();

    private JavaVersion minJavaVersion;
    private JavaVersion maxJavaVersion;

    private BuildInfo info = new BuildInfo();
    /**
     * If the version is correct we never enforce version
     */
    private boolean versionCorrect;

    public InvocationBuilder(BuildRecipeInfo buildInfo, Map<String, List<String>> availableTools, String version) {
        this.buildRecipeInfo = buildInfo;
        this.availableTools = availableTools;
        this.version = version;
    }

    public void setCommitTime(long time) {
        info.commitTime = time;
    }

    public void discoveredToolVersion(String tool, String version) {
        discoveredToolVersions.put(tool, version);
    }

    public void addToolInvocation(String tool, List<String> invocation) {
        if (buildRecipeInfo != null && buildRecipeInfo.getAlternativeArgs() != null) {
            var toolCommand = invocation.get(0);
            List<String> replacement = new ArrayList<>();
            replacement.add(toolCommand);
            replacement.addAll(buildRecipeInfo.getAlternativeArgs());
            toolInvocations.computeIfAbsent(tool, (k) -> new HashSet<>()).add(replacement);
        } else if (buildRecipeInfo != null && buildRecipeInfo.getAdditionalArgs() != null && buildRecipeInfo.getAdditionalArgs().size() > 0) {
            List<String> replacement = new ArrayList<>(invocation);
            replacement.addAll(buildRecipeInfo.getAdditionalArgs());
            toolInvocations.computeIfAbsent(tool, (k) -> new HashSet<>()).add(replacement);
        } else {
            toolInvocations.computeIfAbsent(tool, (k) -> new HashSet<>()).add(invocation);
        }
    }

    /**
     * Sets the minimum Java version.
     *
     * Multiple invocations of the method are allowed, and the highest version is used.
     *
     */
    public void minJavaVersion(JavaVersion min) {
        if (minJavaVersion == null) {
            minJavaVersion = min;
        } else if (minJavaVersion.intVersion() < min.intVersion()) {
            minJavaVersion = min;
        }

    }
    /**
     * Sets the max Java version.
     *
     * Multiple invocations of the method are allowed, and the lowest version is used.
     *
     */
    public void maxJavaVersion(JavaVersion max) {
        if (maxJavaVersion == null) {
            maxJavaVersion = max;
        } else if (maxJavaVersion.intVersion() > max.intVersion()) {
            maxJavaVersion = max;
        }
    }

    public void enforceVersion(String version) {
        info.enforceVersion = version;
    }

    public void addRepository(String repo) {
        if (!info.repositories.contains(repo)) {
            info.repositories.add(repo);
        }
    }

    public void versionCorrect() {
        versionCorrect = true;
    }
    public void existingBuild(List<String> gavList, String fullName, String digest) {
        info.setGavs(gavList);
        info.setImage(fullName);
        info.setDigest(digest);
    }

    public BuildInfo build() {
        if (buildRecipeInfo != null) {
            if (buildRecipeInfo.isEnforceVersion() && !versionCorrect) {
                info.enforceVersion = version;
            }
            if (buildRecipeInfo.getRepositories() != null && !buildRecipeInfo.getRepositories().isEmpty()) {
                info.setRepositories(buildRecipeInfo.getRepositories());
            }
            info.disableSubmodules = buildRecipeInfo.isDisableSubmodules();
            info.preBuildScript = buildRecipeInfo.getPreBuildScript();
            info.postBuildScript = buildRecipeInfo.getPostBuildScript();
            info.setAdditionalDownloads(buildRecipeInfo.getAdditionalDownloads());
            info.setAdditionalMemory(buildRecipeInfo.getAdditionalMemory());
            info.setAllowedDifferences(buildRecipeInfo.getAllowedDifferences());
        }

        return info;
    }

}

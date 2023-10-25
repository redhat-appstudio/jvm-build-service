package com.redhat.hacbs.container.analyser.build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.redhat.hacbs.recipies.build.BuildRecipeInfo;
import com.redhat.hacbs.recipies.tools.BuildToolInfo;

import io.quarkus.logging.Log;

/**
 * Contains the logic around merging build information and discovery results
 */
public class InvocationBuilder {

    final BuildRecipeInfo buildRecipeInfo;
    final Map<String, List<String>> availableTools;
    final String version;

    final Map<String, String> discoveredToolVersions = new HashMap<>();

    final Map<String, Set<List<String>>> toolInvocations = new LinkedHashMap<>();

    JavaVersion minJavaVersion;
    JavaVersion maxJavaVersion;

    BuildInfo info = new BuildInfo();
    /**
     * If the version is correct we never enforce version
     */
    boolean versionCorrect;

    public InvocationBuilder(BuildRecipeInfo buildInfo, Map<String, List<String>> availableTools, String version) {
        this.buildRecipeInfo = buildInfo;
        this.availableTools = availableTools;
        this.version = version;
    }

    public void setCommitTime(long time) {
        info.commitTime = time;
    }

    public void discoveredToolVersion(String tool, String version) {
        if (Objects.equals("jdk", tool)) {
            throw new IllegalArgumentException("cannot use method for JDK");
        }
        discoveredToolVersions.put(tool, version);
    }

    public void addToolInvocation(String tool, List<String> invocation) {
        if (buildRecipeInfo != null && buildRecipeInfo.getAlternativeArgs() != null
                && !buildRecipeInfo.getAlternativeArgs().isEmpty()) {
            toolInvocations.computeIfAbsent(tool, (k) -> new HashSet<>()).add(buildRecipeInfo.getAlternativeArgs());
        } else if (buildRecipeInfo != null && buildRecipeInfo.getAdditionalArgs() != null
                && buildRecipeInfo.getAdditionalArgs().size() > 0) {
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

    public BuildInfo build(CacheBuildInfoLocator buildInfoLocator) {
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
        //now we need to figure out what possible build recipes we can try
        //we work through from lowest Java version to highest
        var javaVersions = new ArrayList<>(
                availableTools.getOrDefault("jdk", List.of()).stream().map(JavaVersion::new).filter(j -> {
                    if (minJavaVersion != null) {
                        if (minJavaVersion.intVersion() > j.intVersion()) {
                            return false;
                        }
                    }
                    if (maxJavaVersion != null) {
                        if (maxJavaVersion.intVersion() < j.intVersion()) {
                            return false;
                        }
                    }
                    return true;
                }).toList());
        Collections.sort(javaVersions);
        Map<String, Map<String, BuildToolInfo>> buildToolInfo = new HashMap<>();
        for (var tool : toolInvocations.keySet()) {
            var result = buildInfoLocator.lookupBuildToolInfo(tool);
            Map<String, BuildToolInfo> versionMap = new HashMap<>();
            buildToolInfo.put(tool, versionMap);
            for (var i : result) {
                versionMap.put(i.getVersion(), i);
            }
        }
        //now select possible tools based on discovered version, or just whatever is available
        Map<String, Set<String>> selectedToolVersions = new HashMap<>();
        for (var entry : toolInvocations.entrySet()) {
            String discovered = null;
            String tool = entry.getKey();
            if (discoveredToolVersions.containsKey(tool)) {
                discovered = discoveredToolVersions.get(tool);
            }
            var toolVersions = availableTools.getOrDefault(tool, List.of());
            if (!toolVersions.isEmpty()) {
                if (discovered == null) {
                    //all tool versions
                    selectedToolVersions.put(tool, new LinkedHashSet<>(toolVersions));
                } else {
                    var closest = findClosestVersions(toolVersions, discovered);
                    if (!closest.isEmpty()) {
                        selectedToolVersions.put(tool, closest);
                    }
                }
            }
        }
        if (!selectedToolVersions.containsKey(BuildInfo.MAVEN)) {
            //we always add a maven version
            //some builds like ant may need it to deploy
            //its not actually explictly invoked though, but MAVEN_HOME will be set
            var toolVersions = availableTools.getOrDefault(BuildInfo.MAVEN, List.of());
            if (!toolVersions.isEmpty()) {
                //todo: make sure the selected version is stable
                selectedToolVersions.put(BuildInfo.MAVEN, Set.of(toolVersions.get(0)));
            }
        }
        //now figure out all possible permuations
        Set<Map<String, String>> allToolPermutations = new HashSet<>();
        String[][] versions = new String[selectedToolVersions.size()][];
        int[] positions = new int[selectedToolVersions.size()];
        String[] toolNames = new String[selectedToolVersions.size()];
        int count = 0;
        for (var i : selectedToolVersions.entrySet()) {
            versions[count] = i.getValue().toArray(new String[0]);
            positions[count] = 0;
            toolNames[count] = i.getKey();
            count++;
        }
        for (;;) {
            Map<String, String> perm = new HashMap<>();
            for (var i = 0; i < versions.length; ++i) {
                perm.put(toolNames[i], versions[i][positions[i]]);
            }
            allToolPermutations.add(perm);
            int current = 0;
            positions[current]++;
            var allDone = false;
            while (positions[current] == versions[current].length) {
                positions[current] = 0;
                current++;
                if (current == versions.length) {
                    allDone = true;
                    break;
                }
                positions[current]++;
            }
            if (allDone) {
                break;
            }
        }

        //now map tool versions to java versions
        for (var invocationSet : toolInvocations.entrySet()) {
            for (var javaVersion : javaVersions) {
                for (var perm : allToolPermutations) {
                    boolean ignore = false;
                    for (var tool : perm.entrySet()) {
                        var info = buildToolInfo.get(tool.getKey());
                        if (info != null) {
                            var toolInfo = info.get(tool.getValue());
                            if (toolInfo != null) {
                                if (toolInfo.getMaxJdkVersion() != null && new JavaVersion(toolInfo.getMaxJdkVersion())
                                        .intVersion() < javaVersion.intVersion()) {
                                    ignore = true;
                                } else if (toolInfo.getMinJdkVersion() != null
                                        && new JavaVersion(toolInfo.getMinJdkVersion()).intVersion() > javaVersion
                                                .intVersion()) {
                                    ignore = true;
                                }
                            }
                        }
                    }
                    if (!ignore) {
                        for (var invocation : invocationSet.getValue()) {
                            Invocation result = new Invocation();
                            HashMap<String, String> toolVersion = new HashMap<>(perm);
                            toolVersion.put(BuildInfo.JDK, javaVersion.version());
                            result.setToolVersion(toolVersion);
                            result.setCommands(invocation);
                            result.setTool(invocationSet.getKey());
                            info.invocations.add(result);
                        }
                    }
                }
            }
        }

        return info;
    }

    static Set<String> findClosestVersions(List<String> toolVersions, String discovered) {
        if (toolVersions.contains(discovered)) {
            return Set.of(discovered);
        }
        Set<String> ret = new HashSet<>();
        //split the version into parts
        String[] disParts = discovered.split("\\.");
        for (var comparePos = disParts.length - 1; comparePos >= 0; comparePos--) {
            //look for matches, first on minor then major
            for (var i : toolVersions) {
                String[] verParts = i.split("\\.");
                if (verParts.length < comparePos) {
                    continue;
                }
                var ok = true;
                for (var j = 0; j <= comparePos; ++j) {
                    if (!Objects.equals(disParts[j], verParts[j])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    ret.add(i);
                }
            }
            if (!ret.isEmpty()) {
                return ret;
            }
        }
        int seekingMajor = -1;
        try {
            seekingMajor = Integer.parseInt(disParts[0]);
        } catch (Exception e) {
            //return everything
            Log.errorf(e, "failed to parse discovered version: %s", discovered);
            return new LinkedHashSet<>(toolVersions);
        }
        //no match, just return the closest we have
        //look for matches, first on minor then major
        int highest = -1;
        int lowest = -1;
        String highestVer = "";
        String lowestVer = "";
        for (var i : toolVersions) {
            String toolMajor = i.split("\\.")[0];
            try {
                int toolVer = Integer.parseInt(toolMajor);
                if (toolVer > seekingMajor) {
                    if (lowest == -1 || toolVer < lowest) {
                        lowest = toolVer;
                        lowestVer = i;
                    }
                } else if (toolVer < seekingMajor) {
                    if (highest == -1 || toolVer > highest) {
                        highest = toolVer;
                        highestVer = i;
                    }
                }
            } catch (NumberFormatException e) {
                Log.errorf(e, "failed to parse version: %s", i);
            }
        }
        if (highest != -1 && lowest != -1) {
            return Set.of(highestVer, lowestVer);
        } else if (highest != -1) {
            return Set.of(highestVer);
        } else if (lowest != -1) {
            return Set.of(lowestVer);
        }
        return new LinkedHashSet<>(toolVersions);
    }

}

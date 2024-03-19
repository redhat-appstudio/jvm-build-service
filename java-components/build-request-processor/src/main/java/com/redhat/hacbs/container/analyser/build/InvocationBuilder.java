package com.redhat.hacbs.container.analyser.build;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.recipes.tools.BuildToolInfo;

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
    JavaVersion preferredJavaVersion;

    BuildInfo info = new BuildInfo();
    /**
     * If the version is correct we never enforce version
     */
    boolean versionCorrect;

    private String contextPath;

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

    public InvocationBuilder setPreferredJavaVersion(JavaVersion preferredJavaVersion) {
        this.preferredJavaVersion = preferredJavaVersion;
        return this;
    }

    public void addToolInvocation(String tool, List<String> invocation) {
        if (buildRecipeInfo != null && buildRecipeInfo.getAlternativeArgs() != null
                && !buildRecipeInfo.getAlternativeArgs().isEmpty()) {
            toolInvocations.computeIfAbsent(tool, (k) -> new HashSet<>()).add(buildRecipeInfo.getAlternativeArgs());
        } else if (buildRecipeInfo != null && buildRecipeInfo.getAdditionalArgs() != null
                && !buildRecipeInfo.getAdditionalArgs().isEmpty()) {
            List<String> replacement = new ArrayList<>(invocation);
            replacement.addAll(buildRecipeInfo.getAdditionalArgs());
            toolInvocations.computeIfAbsent(tool, (k) -> new HashSet<>()).add(replacement);
        } else {
            toolInvocations.computeIfAbsent(tool, (k) -> new HashSet<>()).add(invocation);
        }
    }

    /**
     * Sets the minimum Java version.
     * <br>
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
     * <br>
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
            info.setDisabledPlugins(buildRecipeInfo.getDisabledPlugins());
            if (buildRecipeInfo.getJavaVersion() != null) {
                preferredJavaVersion = new JavaVersion(buildRecipeInfo.getJavaVersion());
            }
        }
        if (preferredJavaVersion != null) {
            //try and match the upstream JDK as closely as possible
            for (var version : availableTools.get("jdk")) {
                JavaVersion j = new JavaVersion(version);
                if (preferredJavaVersion.intVersion() == j.intVersion()) {
                    //exact match
                    minJavaVersion = preferredJavaVersion;
                    maxJavaVersion = preferredJavaVersion;
                    break;
                } else if (j.intVersion() < preferredJavaVersion.intVersion()
                        && (minJavaVersion == null || minJavaVersion.intVersion() < j.intVersion())) {
                    minJavaVersion = j;
                } else if (j.intVersion() > preferredJavaVersion.intVersion()
                        && (maxJavaVersion == null || maxJavaVersion.intVersion() > j.intVersion())) {
                    maxJavaVersion = j;
                }
            }
            Log.infof("Set Java version to [%s, %s] due to preferred version %s", minJavaVersion,
                    maxJavaVersion, preferredJavaVersion);
        }
        Date commitTime = new Date(info.commitTime);
        DateFormat simpleDate = new SimpleDateFormat("yyyy-MM-dd");
        //now we need to figure out what possible build recipes we can try
        //we work through from lowest Java version to highest
        var javaVersions = new ArrayList<JavaVersion>();
        var allPossibleJavaVersions = new ArrayList<JavaVersion>();

        Map<String, Map<String, BuildToolInfo>> buildToolInfo = new HashMap<>();
        Set<String> tools = new HashSet<>(toolInvocations.keySet());
        tools.add("jdk");
        for (var tool : tools) {
            var result = buildInfoLocator.lookupBuildToolInfo(tool);
            Map<String, BuildToolInfo> versionMap = new HashMap<>();
            buildToolInfo.put(tool, versionMap);
            for (var i : result) {
                versionMap.put(i.getVersion(), i);
            }
        }

        //This is a bit hacky, but in general we only care about major release date
        //e.g. if the original build was done with maven 3.9.1 but we are on 3.9.2
        //we want to consider everything released after 3.9.0. It also considers
        //scenarios where 3.8.8 will be reset to release date of the earliest 3.8.x
        //series which is 3.8.1
        for (var e : buildToolInfo.entrySet()) {
            for (var tool : e.getValue().entrySet()) {
                if (tool.getKey().contains(".")) {
                    String toolSubstring = tool.getKey().substring(0, tool.getKey().lastIndexOf('.'));
                    Optional<String> smallestVersion = e.getValue().keySet().stream()
                        .filter(f -> f.matches("^" + toolSubstring + ".*"))
                        .sorted()
                        .findFirst();
                    if (smallestVersion.isPresent()) {
                        var smallestVersionEntry = e.getValue().get(smallestVersion.get()).getReleaseDate();
                        if (!tool.getKey().equals(smallestVersion.get())) {
                            Log.infof("Overriding release date for %s from %s to %s (from %s)",
                                tool.getKey(), tool.getValue().getReleaseDate(), smallestVersionEntry, smallestVersion.get());
                            tool.getValue().setReleaseDate(smallestVersionEntry);
                        }
                    }
                }
            }
        }

        var jdkReleaseInfo = buildToolInfo.get("jdk");
        Date maybeAddReleaseDate = null;
        JavaVersion maybeAddJavaVersion = null;
        List<String> availableJDKs = availableTools.getOrDefault("jdk", List.of());
        for (var javaVersion : availableJDKs) {
            JavaVersion j = new JavaVersion(javaVersion);
            if (minJavaVersion != null) {
                if (minJavaVersion.intVersion() > j.intVersion()) {
                    continue;
                }
            }
            if (maxJavaVersion != null) {
                if (maxJavaVersion.intVersion() < j.intVersion()) {
                    continue;
                }
            }
            allPossibleJavaVersions.add(j);
            BuildToolInfo jdkRelease = jdkReleaseInfo.get("" + j.intVersion());
            if (jdkRelease == null) {
                javaVersions.add(j);
            } else {
                try {
                    Date release = simpleDate.parse(jdkRelease.getReleaseDate());
                    if (release.before(commitTime)) {
                        javaVersions.add(j);
                    } else {
                        //we still want to add this if the commit happened between available tool releases
                        //e.g. if it happened between 8 and 11 it may be targeting 9, which means we might need 11
                        //to build it
                        if (maybeAddReleaseDate == null || maybeAddReleaseDate.after(release)) {
                            maybeAddJavaVersion = j;
                            maybeAddReleaseDate = release;
                        }
                    }
                } catch (ParseException e) {
                    Log.errorf(e, "Failed to parse release date");
                    javaVersions.add(j); //no info, always add it
                }
            }
        }
        if (maybeAddJavaVersion != null) {
            javaVersions.add(maybeAddJavaVersion);
        }
        if (javaVersions.isEmpty()) {
            javaVersions.addAll(allPossibleJavaVersions);
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
                    var possible = buildToolInfo.get(tool);
                    if (possible == null) {
                        //all tool versions
                        selectedToolVersions.put(tool, new LinkedHashSet<>(toolVersions));
                    } else {
                        LinkedHashSet<String> selectedVersions = new LinkedHashSet<>();
                        for (var i : toolVersions) {
                            var bti = possible.get(i);
                            if (bti == null) {
                                selectedVersions.add(i); //no info, always add it
                            } else {
                                try {
                                    //we need to use the release date of the corresponding major version
                                    Date release = simpleDate.parse(bti.getReleaseDate());
                                    if (release.before(commitTime)) {
                                        selectedVersions.add(i); //no info, always add it
                                    }
                                } catch (ParseException e) {
                                    Log.errorf(e, "Failed to parse release date");
                                    selectedVersions.add(i); //no info, always add it
                                }
                            }
                        }
                        if (selectedVersions.isEmpty()) {
                            selectedVersions.addAll(toolVersions);
                        }
                        selectedToolVersions.put(tool, selectedVersions);
                    }
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
            //its not actually explicitly invoked though, but MAVEN_HOME will be set
            var toolVersions = availableTools.getOrDefault(BuildInfo.MAVEN, List.of());
            if (!toolVersions.isEmpty()) {
                //todo: make sure the selected version is stable
                selectedToolVersions.put(BuildInfo.MAVEN, Set.of(toolVersions.get(0)));
            }
        }
        //now figure out all possible permutations
        List<Map<String, String>> allToolPermutations = new ArrayList<>();

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
            Map<String, String> perm = new TreeMap<>();
            for (var i = 0; i < versions.length; ++i) {
                perm.put(toolNames[i], versions[i][positions[i]]);
            }
            // Always add at beginning to effectively reverse the tool order so
            // latest is handled first.
            allToolPermutations.add(0, perm);
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
            for (var perm : allToolPermutations) {
                for (var javaVersion : javaVersions) {
                    boolean ignore = false;
                    var tv = perm.get(invocationSet.getKey());
                    var info = buildToolInfo.get(invocationSet.getKey());
                    if (info != null) {
                        var toolInfo = info.get(tv);
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

                        if (!ignore) {
                            for (var invocation : invocationSet.getValue()) {
                                Invocation result = new Invocation();
                                Map<String, String> toolVersion = new HashMap<>(perm);
                                toolVersion.put(BuildInfo.JDK, javaVersion.version());

                                result.setToolVersion(toolVersion);
                                String tool = invocationSet.getKey();
                                if (tool.equals(BuildInfo.MAVEN)) {
                                    //huge hack, we need a different invocation for different java versions
                                    //Note - according to https://github.com/apache/maven-deploy-plugin/releases
                                    //  the deploy plugin >= 3.1 is JDK8 only.
                                    List<String> cmds = new ArrayList<>(invocation);
                                    if (javaVersion.intVersion() < 8) {
                                        cmds.add("org.apache.maven.plugins:maven-deploy-plugin:3.0.0-M2:deploy");
                                    } else {
                                        cmds.add("org.apache.maven.plugins:maven-deploy-plugin:3.1.1:deploy");
                                    }
                                    result.setCommands(cmds);
                                } else {
                                    result.setCommands(invocation);
                                }
                                result.setTool(tool);
                                result.setDisabledPlugins(
                                        buildRecipeInfo != null && buildRecipeInfo.getDisabledPlugins() != null
                                                ? buildRecipeInfo.getDisabledPlugins()
                                                : buildInfoLocator.lookupDisabledPlugins(tool));
                                this.info.invocations.add(result);
                            }
                        }
                    }
                }
            }
        }
        StringBuilder logging = new StringBuilder();
        info.invocations.forEach(s -> logging.append("\t").append(s).append("\n"));
        Log.infof("InvocationBuilder completed and returning invocations\n%s", logging);
        if (contextPath != null) {
            info.setContextPath(contextPath);
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
        int seekingMajor;
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

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    @Override
    public String toString() {
        return "InvocationBuilder{" +
                "buildRecipeInfo=" + buildRecipeInfo +
                ", availableTools=" + availableTools +
                ", version='" + version + '\'' +
                ", discoveredToolVersions=" + discoveredToolVersions +
                ", toolInvocations=" + toolInvocations +
                ", minJavaVersion=" + minJavaVersion +
                ", maxJavaVersion=" + maxJavaVersion +
                ", info=" + info +
                ", versionCorrect=" + versionCorrect +
                ", contextPath='" + contextPath + '\'' +
                '}';
    }
}

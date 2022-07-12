package com.redhat.hacbs.container.analyser.location;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.build.BuildRecipeInfo;
import com.redhat.hacbs.recipies.location.ProjectBuildRequest;
import com.redhat.hacbs.recipies.location.RecipeDirectory;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeRepositoryManager;
import com.redhat.hacbs.recipies.scm.RepositoryInfo;
import com.redhat.hacbs.recipies.scm.ScmInfo;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "lookup-scm")
public class LookupScmLocationCommand implements Runnable {

    @CommandLine.Option(names = "--recipes", required = true, split = ",")
    List<String> recipeRepos;

    @CommandLine.Option(names = "--gav", required = true)
    String gav;

    //these are paths to files to write the results to for tekton
    @CommandLine.Option(names = "--scm-url")
    Path scmUrl;

    @CommandLine.Option(names = "--scm-type")
    Path scmType;
    @CommandLine.Option(names = "--scm-tag")
    Path scmTag;

    @CommandLine.Option(names = "--message")
    Path message;

    @CommandLine.Option(names = "--context")
    Path context;

    /**
     * The build info, in JSON format as per BuildRecipe.
     * <p>
     * This just gives facts discovered by examining the checkout, it does not make any inferences from those facts (e.g. which
     * image to use).
     */
    @CommandLine.Option(names = "--build-info")
    Path buildInfo;

    @Override
    public void run() {
        try {
            Path tempDir = Files.createTempDirectory("recipe");
            //checkout the git recipe database
            List<RecipeDirectory> managers = new ArrayList<>();
            for (var i : recipeRepos) {
                managers.add(RecipeRepositoryManager.create(i, "main", Optional.empty(), tempDir));
            }
            RecipeGroupManager recipeGroupManager = new RecipeGroupManager(managers);

            GAV toBuild = GAV.parse(gav);
            Log.infof("Looking up %s", gav);
            //look for SCM info
            var recipes = recipeGroupManager
                    .requestBuildInformation(
                            new ProjectBuildRequest(Set.of(toBuild), Set.of(BuildRecipe.SCM, BuildRecipe.BUILD)))
                    .getRecipes()
                    .get(toBuild);
            var deserialized = recipes == null ? null : recipes.get(BuildRecipe.SCM);
            if (recipes == null || deserialized == null) {
                throw new RuntimeException("Failed to find SCM info for " + gav);
            }
            Throwable firstFailure = null;
            Log.infof("Found %s %s", recipes, deserialized);
            ScmInfo main = BuildRecipe.SCM.getHandler().parse(deserialized);
            List<RepositoryInfo> repos = new ArrayList<>();
            repos.add(main);
            if (main.getLegacyRepos() != null) {
                repos.addAll(main.getLegacyRepos());
            }
            BuildRecipeInfo buildRecipeInfo = null;
            Path buildRecipePath = recipes.get(BuildRecipe.BUILD);
            if (buildRecipePath != null) {
                buildRecipeInfo = BuildRecipe.BUILD.getHandler().parse(buildRecipePath);
            }
            for (var parsedInfo : repos) {

                String repoName = null;
                //now look for a tag
                try {
                    String version = toBuild.getVersion();
                    String selectedTag = null;
                    Set<String> exactContains = new HashSet<>();
                    var tags = Git.lsRemoteRepository().setRemote(parsedInfo.getUri()).setTags(true).setHeads(false).call();
                    Set<String> tagNames = tags.stream().map(s -> s.getName().replace("refs/tags/", ""))
                            .collect(Collectors.toSet());

                    //first try tag mappings
                    for (var mapping : main.getTagMapping()) {
                        Log.infof("Trying tag pattern %s on version %s", mapping.getPattern(), version);
                        Matcher m = Pattern.compile(mapping.getPattern()).matcher(version);
                        if (m.matches()) {
                            Log.infof("Tag pattern %s matches", mapping.getPattern());
                            String match = mapping.getTag();
                            for (int i = 1; i <= m.groupCount(); ++i) {
                                match = match.replaceAll("\\$" + i, m.group(i));
                            }
                            Log.infof("Trying to find tag %s", match);
                            //if the tag was a constant we don't require it to be in the tag set
                            //this allows for explicit refs to be used
                            if (tagNames.contains(match) || match.equals(mapping.getTag())) {
                                selectedTag = match;
                                break;
                            }
                        }
                    }

                    if (selectedTag == null) {
                        for (var name : tagNames) {
                            if (name.equals(version)) {
                                //exact match is always good
                                selectedTag = version;
                                break;
                            } else if (name.contains(version)) {
                                exactContains.add(name);
                            }
                        }
                    }
                    if (selectedTag == null) {
                        //no exact match
                        if (exactContains.size() == 1) {
                            //only one contained the full version
                            selectedTag = exactContains.iterator().next();
                        } else {
                            for (var i : exactContains) {
                                //look for a tag that ends with the version (i.e. no -rc1 or similar)
                                if (i.endsWith(version)) {
                                    if (selectedTag == null) {
                                        selectedTag = i;
                                    } else {
                                        throw new RuntimeException(
                                                "Could not determine tag for " + version
                                                        + " multiple possible tags were found: "
                                                        + exactContains);
                                    }
                                }
                            }
                            if (selectedTag == null) {
                                RuntimeException runtimeException = new RuntimeException(
                                        "Could not determine tag for " + version);
                                runtimeException.setStackTrace(new StackTraceElement[0]);
                                throw runtimeException;
                            }
                        }
                    }

                    Log.infof("Found tag %s", selectedTag);
                    if (scmTag != null) {
                        Files.writeString(scmTag, selectedTag);
                    } //write the info we have
                    Log.infof("SCM URL: %s", parsedInfo.getUri());
                    if (scmUrl != null) {
                        Files.writeString(scmUrl, parsedInfo.getUri());
                    }
                    if (scmType != null) {
                        Files.writeString(scmType, "git");
                    }
                    Log.infof("Path: %s", parsedInfo.getPath());
                    if (context != null && parsedInfo.getPath() != null) {
                        Files.writeString(context, parsedInfo.getPath());
                    }

                    doBuildAnalysis(parsedInfo.getUri(), selectedTag, parsedInfo.getPath(), buildRecipeInfo, version);

                    firstFailure = null;
                    break;

                } catch (Exception ex) {
                    Log.error("Failure to determine tag", ex);
                    if (firstFailure == null) {
                        firstFailure = ex;
                    } else {
                        firstFailure.addSuppressed(ex);
                    }
                    throw new RuntimeException(firstFailure);
                }
            }
            if (firstFailure != null) {
                Files.writeString(message,
                        "Failed to determine tag for " + gav + ". Failure reason: " + firstFailure.getMessage());
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build requests");
            if (message != null) {
                try {
                    Files.writeString(message, "Failed to determine tag for " + gav + ". Failure reason: " + e.getMessage());
                } catch (IOException ex) {
                    Log.errorf(e, "Failed to write result");
                }
            }
        }
    }

    private void doBuildAnalysis(String scmUrl, String scmTag, String context, BuildRecipeInfo buildRecipeInfo, String version)
            throws Exception {
        //TODO: this is a basic hack to prove the concept
        var path = Files.createTempDirectory("checkout");
        try (var clone = Git.cloneRepository().setURI(scmUrl).setBranch(scmTag).setDirectory(path.toFile()).call()) {
            if (context != null) {
                path = path.resolve(context);
            }
            BuildInfo info = new BuildInfo();
            info.tools.put("jdk", new VersionRange("8", "17", "11"));
            if (Files.isRegularFile(path.resolve("pom.xml"))) {
                info.tools.put("maven", new VersionRange("3.8", "3.8", "3.8"));
                info.invocations.add(
                        new ArrayList<>(List.of("clean", "install", "-DskipTests", "-Denforcer.skip", "-Dcheckstyle.skip",
                                "-Drat.skip=true", "-Dmaven.deploy.skip=false")));
            } else if (Files.isRegularFile(path.resolve("build.gradle"))
                    || Files.isRegularFile(path.resolve("build.gradle.kts"))) {
                info.tools.put("gradle", new VersionRange("7.3", "7.3", "7.3"));
                info.invocations.add(new ArrayList<>(List.of("gradle", "build")));
            }
            if (buildRecipeInfo != null) {
                if (buildRecipeInfo.getAdditionalArgs() != null) {
                    for (var i : info.invocations) {
                        i.addAll(buildRecipeInfo.getAdditionalArgs());
                    }
                }
                if (buildRecipeInfo.isEnforceVersion()) {
                    info.enforceVersion = version;
                }
                info.setIgnoredArtifacts(buildRecipeInfo.getIgnoredArtifacts());
            }
            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(this.buildInfo.toFile(), info);
        }
    }
}

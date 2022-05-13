package com.redhat.hacbs.container.analyser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jgit.api.Git;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.location.ProjectBuildRequest;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeRepositoryManager;
import com.redhat.hacbs.recipies.scm.ScmInfo;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command
public class LookupBuildRecipesCommand implements Runnable {

    @CommandLine.Option(names = "--recipes", required = true)
    String recipeRepo;

    @CommandLine.Option(names = "--gav", required = true)
    String gav;

    //these are paths to files to write the results to for tekton
    @CommandLine.Option(names = "--scm-url")
    Path scmUrl;

    @CommandLine.Option(names = "--scm-tag")
    Path scmTag;

    @CommandLine.Option(names = "--message")
    Path message;

    @CommandLine.Option(names = "--context")
    Path context;

    @Override
    public void run() {
        try {
            Path tempDir = Files.createTempDirectory("recipe");
            //checkout the git recipe database
            RecipeRepositoryManager manager = RecipeRepositoryManager.create(recipeRepo, "main", Optional.empty(), tempDir);
            RecipeGroupManager recipeGroupManager = new RecipeGroupManager(List.of(manager));
            GAV toBuild = GAV.parse(gav);
            Log.infof("Looking up %s", gav);
            //look for SCM info
            var recipes = recipeGroupManager
                    .requestBuildInformation(new ProjectBuildRequest(Set.of(toBuild), Set.of(BuildRecipe.SCM))).getRecipes()
                    .get(toBuild);
            var scm = recipes == null ? null : recipes.get(BuildRecipe.SCM);
            if (recipes == null || scm == null) {
                throw new RuntimeException("Failed to find SCM info for" + gav);
            }
            Log.infof("Found %s %s", recipes, scm);
            ScmInfo parsedInfo = BuildRecipe.SCM.getHandler().parse(scm);
            //write the info we have
            Log.infof("SCM URL: %s", parsedInfo.getUri());
            if (scmUrl != null) {
                Files.writeString(scmUrl, parsedInfo.getUri());
            }
            Log.infof("Path: %s", parsedInfo.getPath());
            if (context != null && parsedInfo.getPath() != null) {
                Files.writeString(context, parsedInfo.getPath());
            }

            String repoName = null;
            //now look for a tag
            try {
                String version = toBuild.getVersion();
                String selectedTag = null;
                Set<String> exactContains = new HashSet<>();
                var tags = Git.lsRemoteRepository().setRemote(parsedInfo.getUri()).setTags(true).setHeads(false).call();
                for (var tag : tags) {
                    String name = tag.getName().replace("refs/tags/", "");
                    if (name.equals(version)) {
                        //exact match is always good
                        selectedTag = version;
                        break;
                    } else if (name.contains(version)) {
                        exactContains.add(tag.getName());
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
                                            "Could not determine tag for " + version + " multiple possible tags were found: "
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
                }
            } catch (Exception ex) {
                throw new RuntimeException("Failed to determine tag for " + gav + ". Failure reason: " + ex.getMessage());
            }
        } catch (Exception e) {
            Log.errorf(e, "Failed to process build requests");
            if (message != null) {
                try {
                    Files.writeString(message, e.getMessage());
                } catch (IOException ex) {
                    Log.errorf(e, "Failed to write result");
                }
            }
        }
    }

}

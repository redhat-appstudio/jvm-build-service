package com.redhat.hacbs.analyser.repoutils;

import java.nio.file.Path;
import java.util.List;

import javax.inject.Singleton;

import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GitHub;

import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "analyse-github-org")
@Singleton
public class AnalyseGithubOrgCommand implements Runnable {

    @CommandLine.Option(names = "-d", required = true)
    Path data;

    @CommandLine.Option(names = "-o", required = true)
    String org;

    @CommandLine.Option(names = "-l", defaultValue = "false")
    boolean legacy;

    @Override
    public void run() {
        RecipeLayoutManager recipeLayoutManager = new RecipeLayoutManager(
                data);
        RecipeGroupManager groupManager = new RecipeGroupManager(List.of(recipeLayoutManager));

        try {
            GitHub gitHub = GitHub.connect();
            GHOrganization gh = gitHub.getOrganization(org);
            for (var i : gh.getRepositories().entrySet()) {
                if (!i.getValue().isFork() && !i.getValue().isArchived() && !i.getValue().isPrivate()) {
                    try {
                        RepositoryAnalysis.run(recipeLayoutManager, groupManager, i.getValue().getHttpTransportUrl(), legacy);
                    } catch (Exception e) {
                        Log.errorf(e, "Failed to analyse %s", i.getKey());
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

}

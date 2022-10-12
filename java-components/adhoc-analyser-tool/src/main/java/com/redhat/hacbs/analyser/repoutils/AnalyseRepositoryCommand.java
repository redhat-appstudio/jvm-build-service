package com.redhat.hacbs.analyser.repoutils;

import java.nio.file.Path;
import java.util.List;

import javax.inject.Singleton;

import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;

import picocli.CommandLine;

@CommandLine.Command(name = "analyse-repository")
@Singleton
public class AnalyseRepositoryCommand implements Runnable {

    @CommandLine.Option(names = "-d", required = true)
    Path data;

    @CommandLine.Option(names = "-r", required = true)
    String repo;

    @CommandLine.Option(names = "-l", defaultValue = "false")
    boolean legacy;

    @Override
    public void run() {
        RecipeLayoutManager recipeLayoutManager = new RecipeLayoutManager(
                data);
        RecipeGroupManager groupManager = new RecipeGroupManager(List.of(recipeLayoutManager));
        RepositoryAnalysis.run(recipeLayoutManager, groupManager, repo, legacy);
    }

}

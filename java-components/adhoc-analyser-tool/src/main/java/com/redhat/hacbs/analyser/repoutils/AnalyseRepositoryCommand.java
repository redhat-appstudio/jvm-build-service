package com.redhat.hacbs.analyser.repoutils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import jakarta.inject.Singleton;

import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;

import picocli.CommandLine;

@CommandLine.Command(name = "analyse-repository")
@Singleton
public class AnalyseRepositoryCommand implements Runnable {

    @CommandLine.Option(names = "-d", required = false)
    Path data;

    @CommandLine.Option(names = "-r", required = true)
    String repo;

    @CommandLine.Option(names = "-l", defaultValue = "false")
    boolean legacy;

    @Override
    public void run() {
        if (data == null) {
            data = Paths.get(System.getProperty("repo.path"));
        }
        RecipeLayoutManager recipeLayoutManager = new RecipeLayoutManager(
                data);
        RecipeGroupManager groupManager = new RecipeGroupManager(List.of(recipeLayoutManager));
        RepositoryAnalysis.run(recipeLayoutManager, groupManager, repo, legacy);
    }

}

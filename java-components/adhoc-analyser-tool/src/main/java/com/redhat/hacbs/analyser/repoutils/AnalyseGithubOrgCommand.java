package com.redhat.hacbs.analyser.repoutils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.inject.Singleton;

import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GitHub;

import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "analyse-github-org")
@Singleton
public class AnalyseGithubOrgCommand implements Runnable {

    @CommandLine.Option(names = "-d", required = false)
    Path data;

    @CommandLine.Option(names = "-o", required = true)
    String org;

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

        ExecutorService ex = Executors.newFixedThreadPool(10);

        try {
            GitHub gitHub = GitHub.connect();
            GHOrganization gh = gitHub.getOrganization(org);
            List<Future<?>> list = new ArrayList<>();
            for (var i : gh.getRepositories().entrySet()) {
                String lang = i.getValue().getLanguage();
                if (lang != null && (lang.toLowerCase(Locale.ENGLISH).equals("java")
                        || lang.toLowerCase(Locale.ENGLISH).equals("kotlin"))) {
                    if (!i.getValue().isFork() && !i.getValue().isArchived() && !i.getValue().isPrivate()) {
                        list.add(ex.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {

                                    RepositoryAnalysis.run(recipeLayoutManager, groupManager,
                                            i.getValue().getHttpTransportUrl(), legacy);

                                } catch (Exception e) {
                                    Log.errorf(e, "Failed to analyse %s", i.getKey());
                                }
                            }
                        }));
                    }
                }

            }
            for (var i : list) {
                i.get();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

}

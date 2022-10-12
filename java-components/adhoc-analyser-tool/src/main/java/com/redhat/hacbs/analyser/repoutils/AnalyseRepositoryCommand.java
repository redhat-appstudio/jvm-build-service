package com.redhat.hacbs.analyser.repoutils;

import com.redhat.hacbs.analyser.data.scm.Repository;
import com.redhat.hacbs.analyser.maven.GradleAnalyser;
import com.redhat.hacbs.analyser.maven.MavenAnalyser;
import com.redhat.hacbs.analyser.maven.MavenProject;
import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.location.AddRecipeRequest;
import com.redhat.hacbs.recipies.location.ArtifactInfoRequest;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;
import com.redhat.hacbs.recipies.scm.RepositoryInfo;
import com.redhat.hacbs.recipies.scm.ScmInfo;
import io.quarkus.dev.console.QuarkusConsole;
import io.quarkus.dev.console.StatusLine;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ApplicationLifecycleManager;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ProgressMonitor;
import picocli.CommandLine;

import javax.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

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

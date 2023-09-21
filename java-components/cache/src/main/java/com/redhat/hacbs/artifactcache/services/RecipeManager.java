package com.redhat.hacbs.artifactcache.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.redhat.hacbs.recipies.tools.BuildToolInfo;
import com.redhat.hacbs.recipies.tools.BuildToolInfoManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.build.BuildRecipeInfo;
import com.redhat.hacbs.recipies.location.BuildInfoRequest;
import com.redhat.hacbs.recipies.location.RecipeDirectory;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeRepositoryManager;
import com.redhat.hacbs.recipies.mavenrepo.MavenRepositoryInfo;
import com.redhat.hacbs.recipies.mavenrepo.MavenRepositoryInfoManager;
import com.redhat.hacbs.recipies.scm.GitScmLocator;
import com.redhat.hacbs.recipies.util.FileUtil;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;

@Startup
@Singleton
public class RecipeManager {

    @Inject
    CachePomScmLocator cachePomScmLocator;

    @ConfigProperty(name = "build-info.repositories", defaultValue = "https://github.com/redhat-appstudio/jvm-build-data")
    List<String> buildInfoRepos;

    final List<RecipeDirectory> recipeDirs = new ArrayList<>();
    final List<Path> tempFiles = new ArrayList<>();
    RecipeGroupManager recipeGroupManager;

    @PostConstruct
    void setup() throws IOException, GitAPIException {
        for (var i : buildInfoRepos) {
            Path tempDir = Files.createTempDirectory("recipe");
            Log.infof("Reading repos from %s at %s", i, tempDir);
            tempFiles.add(tempDir);
            recipeDirs
                    .add(RecipeRepositoryManager.create(i, Optional.of(Duration.of(1, ChronoUnit.MINUTES)), tempDir));
        }
        recipeGroupManager = new RecipeGroupManager(recipeDirs);
    }

    public void forceUpdate() {
        recipeGroupManager.forceUpdate();
    }

    @PreDestroy
    void clear() {
        for (var i : tempFiles) {
            FileUtil.deleteRecursive(i);
        }
    }

    public GitScmLocator locator() {
        return GitScmLocator.builder()
                .setRecipeGroupManager(recipeGroupManager)
                .setFallback(cachePomScmLocator)
                .build();
    }

    public List<MavenRepositoryInfo> getRepositoryInfo(String repo) {
        List<MavenRepositoryInfo> ret = new ArrayList<>();
        for (var i : recipeDirs) {
            var path = i.getRepositoryPaths(repo);
            if (path.isPresent()) {
                try {
                    ret.add(MavenRepositoryInfoManager.INSTANCE.parse(path.get()));
                } catch (IOException e) {
                    Log.errorf(e, "Failed to parse repository info file %s", path.get());
                }
            }
        }
        return ret;
    }

    public Map<String, MavenRepositoryInfo> getAllRepositoryInfo() {
        Map<String, MavenRepositoryInfo> ret = new HashMap<>();
        for (var i : recipeDirs) {
            var paths = i.getAllRepositoryPaths();
            for (var path : paths) {
                try {
                    ret.put(path.getFileName().toString().replace(".yaml", ""),
                            MavenRepositoryInfoManager.INSTANCE.parse(path));
                } catch (IOException e) {
                    Log.errorf(e, "Failed to parse repository info file %s", path);
                }
            }
        }
        return ret;
    }

    public List<BuildToolInfo> getBuildToolInfo(String name) {
        TreeMap<String, BuildToolInfo> results = new TreeMap<>();
        for (var i : recipeDirs) {
            var path = i.getBuildToolInfo(name);
            if (path.isPresent()) {
                try {
                    for (var b : BuildToolInfoManager.INSTANCE.parse(path.get())) {
                        results.put(b.getVersion(), b);

                    }
                } catch (IOException e) {
                    Log.errorf(e, "Failed to parse build tool info file %s", path);
                }
            }
        }
        return new ArrayList<>(results.values());
    }

    public BuildRecipeInfo resolveBuildInfo(String scmUrl, String version) throws IOException {

        var ret = recipeGroupManager.requestBuildInformation(new BuildInfoRequest(scmUrl, version, Set.of(BuildRecipe.BUILD)));
        Path path = ret.getData().get(BuildRecipe.BUILD);
        if (path == null) {
            return null;
        }
        return BuildRecipe.BUILD.getHandler().parse(path);
    }
}

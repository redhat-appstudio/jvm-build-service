package com.redhat.hacbs.analyser.repoutils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jgit.api.Git;

import com.redhat.hacbs.analyser.maven.GradleAnalyser;
import com.redhat.hacbs.analyser.maven.MavenAnalyser;
import com.redhat.hacbs.analyser.maven.MavenProject;
import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.location.AddRecipeRequest;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;
import com.redhat.hacbs.recipies.scm.RepositoryInfo;
import com.redhat.hacbs.recipies.scm.ScmInfo;

public class RepositoryAnalysis {

    public static void run(RecipeLayoutManager recipeLayoutManager, RecipeGroupManager groupManager, String repo,
            boolean legacy) {
        Map<String, String> doubleUps = new TreeMap<>();
        Set<Path> doubleUpFiles = new HashSet<>();
        try {
            Path checkoutPath = Files.createTempDirectory("repo-analysis");
            Git checkout;
            System.out.println("Checking out " + repo + " into " + checkoutPath);
            checkout = Git.cloneRepository().setDirectory(checkoutPath.toFile())
                    .setURI(repo).call();
            try (checkout) {
                analyseRepository(doubleUps, doubleUpFiles, recipeLayoutManager, groupManager, repo, checkoutPath, legacy);
            }
            for (var e : doubleUps.entrySet()) {
                System.out.println("DOUBLE UP: " + e.getKey() + " " + e.getValue());
            }
            for (var path : doubleUpFiles) {
                Files.delete(path);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean analyseRepository(Map<String, String> doubleUps, Set<Path> doubleUpFiles,
            RecipeLayoutManager recipeLayoutManager, RecipeGroupManager groupManager, String repository, Path checkoutPath,
            boolean legacy)
            throws IOException {
        MavenProject result = analyseProject(checkoutPath);
        if (result == null)
            return true;
        for (var module : result.getProjects().values()) {
            var existing = groupManager
                    .lookupScmInformation(module.getGav());
            if (!legacy) {
                if (!existing.isEmpty()) {
                    ScmInfo existingInfo = BuildRecipe.SCM.getHandler().parse(existing.get(0));
                    if (existingInfo.getUri().equals(repository)) {
                        continue;
                    }
                    if (existing.get(0).toString().contains("_artifact")) {
                        doubleUps.put(existingInfo.getUri(), repository + "  " + module.getGav());
                        doubleUpFiles.add(existing.get(0));
                    }
                }
                ScmInfo info = new ScmInfo("git", repository, result.getPath());
                recipeLayoutManager.writeArtifactData(new AddRecipeRequest<>(BuildRecipe.SCM, info,
                        module.getGav().getGroupId(), module.getGav().getArtifactId(), null));

            } else {
                //legacy mode, we just want to add legacy info to an existing file
                if (!existing.isEmpty()) {
                    ScmInfo existingInfo = BuildRecipe.SCM.getHandler().parse(existing.get(0));
                    boolean found = false;
                    for (var existingLegacy : existingInfo.getLegacyRepos()) {
                        if (existingLegacy.getUri().equals(repository)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        existingInfo.getLegacyRepos().add(new RepositoryInfo("git", repository, result.getPath()));
                    }
                    BuildRecipe.SCM.getHandler().write(existingInfo, existing.get(0));
                }

            }
        }

        return false;
    }

    private static MavenProject analyseProject(Path checkoutPath) {
        MavenProject result;
        Path path = findBuildDir(checkoutPath, List.of("pom.xml", "build.gradle"));
        if (path == null) {
            return null;
        }
        if (Files.exists(path.resolve("pom.xml"))) {
            result = MavenAnalyser.doProjectDiscovery(path);
        } else if (Files.exists(path.resolve("build.gradle"))
                || Files.exists(path.resolve("build.gradle.kts"))) {
            result = GradleAnalyser.doProjectDiscovery(path);
        } else {
            return null;
        }
        if (!path.equals(checkoutPath)) {
            result.setPath(checkoutPath.relativize(path).toString());
        }
        return result;
    }

    private static Path findBuildDir(Path start, List<String> buildFiles) {
        for (var i : buildFiles) {
            if (Files.exists(start.resolve(i))) {
                return start;
            }
        }
        Path result = null;
        try (var stream = Files.newDirectoryStream(start)) {
            for (var i : stream) {
                if (Files.isDirectory(i)) {
                    var r = findBuildDir(i, buildFiles);
                    if (r != null) {
                        if (result == null) {
                            result = r;
                        } else {
                            //more than one possible sub path
                            return null;
                        }

                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return result;

    }
}

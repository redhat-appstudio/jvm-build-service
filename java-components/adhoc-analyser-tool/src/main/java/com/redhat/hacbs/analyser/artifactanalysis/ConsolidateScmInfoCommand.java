package com.redhat.hacbs.analyser.artifactanalysis;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.redhat.hacbs.analyser.config.CheckoutConfig;
import com.redhat.hacbs.analyser.config.RepoConfig;
import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;
import com.redhat.hacbs.recipies.location.RecipeRepositoryManager;
import com.redhat.hacbs.recipies.scm.ScmInfo;

import picocli.CommandLine;

@CommandLine.Command(name = "consolidate-scm-info", description = "Removes all _artifact level scm info and merged it into the group level when possible")
@Singleton
public class ConsolidateScmInfoCommand implements Runnable {

    @Inject
    CheckoutConfig checkoutConfig;

    @Inject
    RepoConfig repoConfig;

    @Override
    public void run() {
        Path recipeBase = repoConfig.path().resolve(RecipeRepositoryManager.SCM_INFO);
        try {
            Files.walkFileTree(recipeBase, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.getFileName().toString().equals(RecipeLayoutManager.ARTIFACT)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (dir.getFileName().toString().equals(RecipeLayoutManager.VERSION)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path artifactDirectory = dir.resolve(RecipeLayoutManager.ARTIFACT);
                    if (Files.exists(artifactDirectory)) {
                        consolidateGroupIfPossible(artifactDirectory);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void consolidateGroupIfPossible(Path artifactDirectory) throws IOException {
        Path path = artifactDirectory.getParent().resolve(BuildRecipe.SCM.getName());
        if (Files.exists(path)) {
            return;
        }
        Map<String, List<Path>> toDelete = new HashMap<>();
        AtomicInteger totalCount = new AtomicInteger();
        Map<String, AtomicInteger> perScmUriCount = new HashMap<>();
        Files.walkFileTree(artifactDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName().toString().equals(RecipeLayoutManager.VERSION)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals(BuildRecipe.SCM.getName())) {
                    ScmInfo current = BuildRecipe.SCM.getHandler().parse(file);
                    totalCount.incrementAndGet();
                    toDelete.computeIfAbsent(current.getUri(), s -> new ArrayList<>()).add(file);
                    perScmUriCount.computeIfAbsent(current.getUri(), s -> new AtomicInteger()).incrementAndGet();

                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (var entry : perScmUriCount.entrySet()) {
            if (entry.getValue().get() > (totalCount.get() / 2)) {
                Files.copy(toDelete.get(entry.getKey()).get(0), path);
                toDelete.get(entry.getKey()).forEach(s -> {
                    try {
                        Files.delete(s);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                break;
            }
        }

    }

}

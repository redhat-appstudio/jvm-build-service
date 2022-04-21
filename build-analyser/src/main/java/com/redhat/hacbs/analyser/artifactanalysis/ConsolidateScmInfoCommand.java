package com.redhat.hacbs.analyser.artifactanalysis;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

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
        Path recipeBase = repoConfig.path().resolve(RecipeRepositoryManager.RECIPES);
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
        AtomicReference<ScmInfo> first = new AtomicReference<>();
        List<Path> toDelete = new ArrayList<>();
        try {
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
                        toDelete.add(file);
                        ScmInfo current = BuildRecipe.SCM.getHandler().parse(file);
                        if (first.get() == null) {
                            first.set(current);
                        } else {
                            if (!Objects.equals(current.getUri(), first.get().getUri()) ||
                                    !Objects.equals(current.getType(), first.get().getType())) {
                                throw new CantConsolidateException();
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            BuildRecipe.SCM.getHandler().write(first.get(), artifactDirectory.getParent().resolve(BuildRecipe.SCM.getName()));
            for (var i : toDelete) {
                Files.delete(i);
                try (Stream<Path> stream = Files.list(i.getParent()) ) {
                    if (stream.findAny().isEmpty()) {
                        Files.delete(i.getParent());
                    }
                }
            }
        } catch (CantConsolidateException e) {
            return;
        }

    }

    static class CantConsolidateException extends RuntimeException {

    }

}

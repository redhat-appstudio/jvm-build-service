package io.github.redhatappstudio.jvmbuild.cli.repo;

import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;

import java.nio.file.Path;

/**
 * Representation of an operation that creates a PR on the recipe repository
 */
public interface PullRequestCreator {

    String branchName();
    String commitMessage();

    void makeModifications(Path repositoryRoot, RecipeGroupManager recipeGroupManager, RecipeLayoutManager recipeLayoutManager) throws Exception;

}

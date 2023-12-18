package com.redhat.hacbs.common.tools.repo;

import java.nio.file.Path;

import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;

/**
 * Representation of an operation that creates a PR on the recipe repository
 */
public interface PullRequestCreator {

    void makeModifications(Path repositoryRoot, RecipeGroupManager recipeGroupManager, RecipeLayoutManager recipeLayoutManager)
            throws Exception;

}

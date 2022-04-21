package com.redhat.hacbs.recipies.location;

import java.nio.file.Path;
import java.util.Map;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;

public class ProjectBuildResponse {

    /**
     * A map of build requests to paths for the respective build files
     */
    final Map<GAV, Map<BuildRecipe, Path>> recipes;

    public ProjectBuildResponse(Map<GAV, Map<BuildRecipe, Path>> recipes) {
        this.recipes = recipes;
    }

    public Map<GAV, Map<BuildRecipe, Path>> getRecipes() {
        return recipes;
    }
}

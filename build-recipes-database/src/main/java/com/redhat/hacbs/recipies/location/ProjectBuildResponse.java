package com.redhat.hacbs.recipies.location;

import java.nio.file.Path;
import java.util.Map;

public class ProjectBuildResponse {

    /**
     * A map of build requests to paths for the respective build files
     */
    final Map<BuildLocationRequest, Map<BuildRecipe, Path>> recipes;

    public ProjectBuildResponse(Map<BuildLocationRequest, Map<BuildRecipe, Path>> recipes) {
        this.recipes = recipes;
    }

    public Map<BuildLocationRequest, Map<BuildRecipe, Path>> getRecipes() {
        return recipes;
    }
}

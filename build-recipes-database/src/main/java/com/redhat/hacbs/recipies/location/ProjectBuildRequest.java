package com.redhat.hacbs.recipies.location;

import java.util.Set;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;

/**
 * Encapsulates a request for the location of all project dependencies. This is more efficient than requesting
 * a result from each artifact individually.
 */
public class ProjectBuildRequest {

    private final Set<GAV> requests;
    private final Set<BuildRecipe> recipeFiles;

    public ProjectBuildRequest(Set<GAV> requests, Set<BuildRecipe> recipeFiles) {
        this.requests = requests;
        this.recipeFiles = recipeFiles;
    }

    public Set<GAV> getRequests() {
        return requests;
    }

    public Set<BuildRecipe> getRecipeFiles() {
        return recipeFiles;
    }
}

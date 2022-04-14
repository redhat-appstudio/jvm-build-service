package com.redhat.hacbs.recipies.location;

import java.util.Set;

/**
 * Encapsulates a request for the location of all project dependencies. This is more efficient than requesting
 * a result from each artifact individually.
 */
public class ProjectBuildRequest {

    private final Set<BuildLocationRequest> requests;
    private final Set<BuildRecipe> recipeFiles;

    public ProjectBuildRequest(Set<BuildLocationRequest> requests, Set<BuildRecipe> recipeFiles) {
        this.requests = requests;
        this.recipeFiles = recipeFiles;
    }

    public Set<BuildLocationRequest> getRequests() {
        return requests;
    }

    public Set<BuildRecipe> getRecipeFiles() {
        return recipeFiles;
    }
}

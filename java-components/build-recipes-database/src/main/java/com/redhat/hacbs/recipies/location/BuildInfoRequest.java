package com.redhat.hacbs.recipies.location;

import java.util.Set;

import com.redhat.hacbs.recipies.BuildRecipe;

public class BuildInfoRequest {

    private final String scmUri;
    private final String version;
    private final Set<BuildRecipe> recipeFiles;

    public BuildInfoRequest(String scmUri, String version, Set<BuildRecipe> recipeFiles) {
        this.scmUri = scmUri;
        this.version = version;
        this.recipeFiles = recipeFiles;
    }

    public Set<BuildRecipe> getRecipeFiles() {
        return recipeFiles;
    }

    public String getScmUri() {
        return scmUri;
    }

    public String getVersion() {
        return version;
    }
}

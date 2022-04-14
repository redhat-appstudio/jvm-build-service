package com.redhat.hacbs.recipies;

/**
 * All the info we know about how to do a specific build
 */
public class BuildRecipe {

    final String scmUrl;

    public BuildRecipe(String scmUrl) {
        this.scmUrl = scmUrl;
    }

    public String getScmUrl() {
        return scmUrl;
    }
}

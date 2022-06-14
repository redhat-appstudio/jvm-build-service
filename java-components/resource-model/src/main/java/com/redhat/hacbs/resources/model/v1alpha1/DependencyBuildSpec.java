package com.redhat.hacbs.resources.model.v1alpha1;

import java.util.List;

public class DependencyBuildSpec {

    private ScmInfo scm;

    private String version;

    private List<BuildRecipe> buildRecipes;

    public ScmInfo getScm() {
        return scm;
    }

    public DependencyBuildSpec setScm(ScmInfo scm) {
        this.scm = scm;
        return this;
    }

    public String getVersion() {
        return version;
    }

    public DependencyBuildSpec setVersion(String version) {
        this.version = version;
        return this;
    }

    public List<BuildRecipe> getBuildRecipes() {
        return buildRecipes;
    }

    public DependencyBuildSpec setBuildRecipes(List<BuildRecipe> buildRecipes) {
        this.buildRecipes = buildRecipes;
        return this;
    }
}

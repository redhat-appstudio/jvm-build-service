package com.redhat.hacbs.resources.model.v1alpha1;

public class ArtifactBuildStatus {

    private String state = "";
    private String recipeGitHash; //todo multiple git repos
    private String discoveryTaskRun;
    private String message;

    private ScmInfo scm;

    public String getState() {
        return state;
    }

    public ArtifactBuildStatus setState(String state) {
        this.state = state;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public ArtifactBuildStatus setMessage(String message) {
        this.message = message;
        return this;
    }

    public String getRecipeGitHash() {
        return recipeGitHash;
    }

    public ArtifactBuildStatus setRecipeGitHash(String recipeGitHash) {
        this.recipeGitHash = recipeGitHash;
        return this;
    }

    public String getDiscoveryTaskRun() {
        return discoveryTaskRun;
    }

    public ArtifactBuildStatus setDiscoveryTaskRun(String discoveryTaskRun) {
        this.discoveryTaskRun = discoveryTaskRun;
        return this;
    }

    public ScmInfo getScm() {
        return scm;
    }

    public ArtifactBuildStatus setScm(ScmInfo scm) {
        this.scm = scm;
        return this;
    }
}

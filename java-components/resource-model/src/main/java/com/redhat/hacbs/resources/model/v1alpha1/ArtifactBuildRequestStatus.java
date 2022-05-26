package com.redhat.hacbs.resources.model.v1alpha1;

public class ArtifactBuildRequestStatus {

    private String state = "";
    private String recipeGitHash; //todo multiple git repos
    private String discoveryTaskRun;
    private String message;

    private ScmInfo scm;

    public String getState() {
        return state;
    }

    public ArtifactBuildRequestStatus setState(String state) {
        this.state = state;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public ArtifactBuildRequestStatus setMessage(String message) {
        this.message = message;
        return this;
    }

    public String getRecipeGitHash() {
        return recipeGitHash;
    }

    public ArtifactBuildRequestStatus setRecipeGitHash(String recipeGitHash) {
        this.recipeGitHash = recipeGitHash;
        return this;
    }

    public String getDiscoveryTaskRun() {
        return discoveryTaskRun;
    }

    public ArtifactBuildRequestStatus setDiscoveryTaskRun(String discoveryTaskRun) {
        this.discoveryTaskRun = discoveryTaskRun;
        return this;
    }

    public ScmInfo getScm() {
        return scm;
    }

    public ArtifactBuildRequestStatus setScm(ScmInfo scm) {
        this.scm = scm;
        return this;
    }
}

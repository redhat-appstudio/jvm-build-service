package com.redhat.hacbs.resources.model.v1alpha1;

public class ArtifactBuildRequestStatus {

    private String state = "";
    private String recipeGitHash; //todo multiple git repos
    private String discoveryTaskRun;
    private String scmType;
    private String scmURL;
    private String message;
    private String tag;

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

    public String getScmType() {
        return scmType;
    }

    public ArtifactBuildRequestStatus setScmType(String scmType) {
        this.scmType = scmType;
        return this;
    }

    public String getScmURL() {
        return scmURL;
    }

    public ArtifactBuildRequestStatus setScmURL(String scmURL) {
        this.scmURL = scmURL;
        return this;
    }

    public String getTag() {
        return tag;
    }

    public ArtifactBuildRequestStatus setTag(String tag) {
        this.tag = tag;
        return this;
    }
}

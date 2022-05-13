package com.redhat.hacbs.resources.model.v1alpha1;

public class ArtifactBuildRequestStatus {

    private String state = "";
    private String recipeGitHash; //todo multiple git repos
    private String buildPipelineName; //todo multiple git repos
    private String message;

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
}

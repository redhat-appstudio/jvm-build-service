package com.redhat.hacbs.resources.model.v1alpha1;

import java.util.List;

public class DependencyBuildStatus {

    private String state;
    private List<Contaminant> contaminates;

    private BuildRecipe currentBuildRecipe;
    private List<BuildRecipe> failedBuildRecipes;
    private List<BuildRecipe> potentialBuildRecipes;

    private String lastCompletedBuildPipelineRun;

    private long commitTime;

    public String getState() {
        return state;
    }

    public DependencyBuildStatus setState(String state) {
        this.state = state;
        return this;
    }

    public List<Contaminant> getContaminates() {
        return contaminates;
    }

    public DependencyBuildStatus setContaminates(List<Contaminant> contaminates) {
        this.contaminates = contaminates;
        return this;
    }

    public BuildRecipe getCurrentBuildRecipe() {
        return currentBuildRecipe;
    }

    public DependencyBuildStatus setCurrentBuildRecipe(BuildRecipe currentBuildRecipe) {
        this.currentBuildRecipe = currentBuildRecipe;
        return this;
    }

    public List<BuildRecipe> getFailedBuildRecipes() {
        return failedBuildRecipes;
    }

    public DependencyBuildStatus setFailedBuildRecipes(List<BuildRecipe> failedBuildRecipes) {
        this.failedBuildRecipes = failedBuildRecipes;
        return this;
    }

    public List<BuildRecipe> getPotentialBuildRecipes() {
        return potentialBuildRecipes;
    }

    public DependencyBuildStatus setPotentialBuildRecipes(List<BuildRecipe> potentialBuildRecipes) {
        this.potentialBuildRecipes = potentialBuildRecipes;
        return this;
    }

    public String getLastCompletedBuildPipelineRun() {
        return lastCompletedBuildPipelineRun;
    }

    public DependencyBuildStatus setLastCompletedBuildPipelineRun(String lastCompletedBuildPipelineRun) {
        this.lastCompletedBuildPipelineRun = lastCompletedBuildPipelineRun;
        return this;
    }

    public long getCommitTime() {
        return commitTime;
    }

    public DependencyBuildStatus setCommitTime(long commitTime) {
        this.commitTime = commitTime;
        return this;
    }
}

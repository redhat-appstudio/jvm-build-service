package com.redhat.hacbs.resources.model.v1alpha1;

import java.util.List;

public class BuildRecipe {

    private String image;
    private List<String> commandLine;

    public String getImage() {
        return image;
    }

    public BuildRecipe setImage(String image) {
        this.image = image;
        return this;
    }

    public List<String> getCommandLine() {
        return commandLine;
    }

    public BuildRecipe setCommandLine(List<String> commandLine) {
        this.commandLine = commandLine;
        return this;
    }
}

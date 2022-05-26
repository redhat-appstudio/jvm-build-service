package com.redhat.hacbs.resources.model.v1alpha1;

public class BuildRecipe {

    private String image;

    public String getImage() {
        return image;
    }

    public BuildRecipe setImage(String image) {
        this.image = image;
        return this;
    }
}

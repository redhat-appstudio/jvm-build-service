package com.redhat.hacbs.recipes.location;

import java.nio.file.Path;
import java.util.Map;

import com.redhat.hacbs.recipes.BuildRecipe;

public class BuildInfoResponse {
    final Map<BuildRecipe, Path> data;

    public BuildInfoResponse(Map<BuildRecipe, Path> data) {
        this.data = data;
    }

    public Map<BuildRecipe, Path> getData() {
        return data;
    }
}

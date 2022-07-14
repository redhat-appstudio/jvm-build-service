package com.redhat.hacbs.recipies.location;

import java.nio.file.Path;
import java.util.Map;

import com.redhat.hacbs.recipies.BuildRecipe;

public class BuildInfoResponse {
    final Map<BuildRecipe, Path> data;

    public BuildInfoResponse(Map<BuildRecipe, Path> data) {
        this.data = data;
    }

    public Map<BuildRecipe, Path> getData() {
        return data;
    }
}

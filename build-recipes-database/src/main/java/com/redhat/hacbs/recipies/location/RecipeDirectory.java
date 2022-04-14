package com.redhat.hacbs.recipies.location;

import java.util.Optional;

public interface RecipeDirectory {

    /**
     * Returns the directories that contain the recipe information for this specific artifact
     *
     * @param groupId
     * @param artifactId
     * @param version
     * @return
     */
    Optional<RecipePathMatch> getArtifactPaths(String groupId, String artifactId, String version);
}

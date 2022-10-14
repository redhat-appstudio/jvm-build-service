package com.redhat.hacbs.recipies.location;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages an individual recipe database of build recipes.
 * <p>
 * Layout is specified as:
 * <p>
 * /recipes/io/quarkus/ //location information for everything in the io/quarkus group
 * /recipes/io/quarkus/security/ //info for the io.quarkus.security group
 * /recipes/io/quarkus/_artifact/quarkus-core/ //artifact level information for quarkus-core (hopefully not common)
 * /recipes/io/quarkus/_version/2.2.0-rhosk3/ //location information for version 2.2.0-rhosk3
 * /recipes/io/quarkus/_artifact/quarkus-core/_version/2.2.0-rhosk3/ //artifact level information for a specific version of
 * quarkus core
 * <p>
 * Different pieces of information are stored in different files in these directories specified above, and it is possible
 * to only override some parts of the recipe (e.g. a different location for a service specific version, but everything else is
 * the same)
 * <p>
 * At present this is just the location information.
 */
public class RecipeLayoutManager implements RecipeDirectory {

    private static final Logger log = Logger.getLogger(RecipeLayoutManager.class.getName());

    public static final String ARTIFACT = "_artifact";
    public static final String VERSION = "_version";

    private final Path checkoutDirectory;
    private final Path scmInfoDirectory;
    private final Path buildInfoDirectory;

    public RecipeLayoutManager(Path baseDirectory) {
        this.checkoutDirectory = baseDirectory;
        Path legacy = baseDirectory.resolve(RecipeRepositoryManager.RECIPES);
        Path expected = baseDirectory.resolve(RecipeRepositoryManager.SCM_INFO);
        this.scmInfoDirectory = Files.isDirectory(expected) ? expected : legacy;
        this.buildInfoDirectory = baseDirectory.resolve(RecipeRepositoryManager.BUILD_INFO);
    }

    /**
     * Returns the directories that contain the recipe information for this specific artifact
     */
    public Optional<RecipePathMatch> getArtifactPaths(String groupId, String artifactId, String version) {
        Path groupPath = this.scmInfoDirectory.resolve(groupId.replace('.', File.separatorChar));
        Path artifactFolder = groupPath.resolve(ARTIFACT);
        Path artifactPath = artifactFolder.resolve(artifactId);
        Path versionFolder = groupPath.resolve(VERSION);
        Path versionPath = versionFolder.resolve(version);
        Path artifactAndVersionPath = artifactPath.resolve(VERSION).resolve(version);
        if (!Files.exists(groupPath)) {
            return Optional.empty();
        }
        return Optional.of(new RecipePathMatch(groupPath,
                Files.exists(artifactPath) ? artifactPath : null,
                Files.exists(versionPath) ? versionPath : null,
                Files.exists(artifactAndVersionPath) ? artifactAndVersionPath : null,
                !Files.exists(artifactFolder) && !Files.exists(versionFolder)));
    }

    @Override
    public Optional<Path> getBuildPaths(String scmUri, String tag) {
        Path target = buildInfoDirectory.resolve(scmUri);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    @Override
    public <T> void writeArtifactData(AddRecipeRequest<T> data) {
        String groupId = data.getGroupId();
        String artifactId = data.getArtifactId();
        String version = data.getVersion();
        Path resolved = this.scmInfoDirectory.resolve(groupId.replace('.', File.separatorChar));
        if (artifactId != null) {
            resolved = resolved.resolve(ARTIFACT);
            resolved = resolved.resolve(artifactId);
        }
        if (version != null) {
            resolved = resolved.resolve(VERSION);
            resolved = resolved.resolve(version);
        }
        try {
            Files.createDirectories(resolved);
            data.getRecipe().getHandler().write(data.getData(), resolved.resolve(data.getRecipe().getName()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

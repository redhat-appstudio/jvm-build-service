package com.redhat.hacbs.recipies.location;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

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
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

    public static final String ARTIFACT = "_artifact";
    public static final String VERSION = "_version";
    public static final String REDIRECT_YAML = "redirect.yaml";
    public static final String LOCATION = "location";
    public static final String ARTIFACT_ID = "artifact-id";
    public static final String GROUP_ID = "group-id";
    public static final String VERSION_ID = "version";

    private final Path baseDirectory;

    public RecipeLayoutManager(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    /**
     * Returns the directories that contain the recipe information for this specific artifact
     */
    public Optional<RecipePathMatch> getArtifactPaths(String groupId, String artifactId, String version) {
        return getArtifactPaths(groupId, artifactId, version, new LinkedHashSet<>());
    }

    /**
     * Returns the directories that contain the recipe information for this specific artifact
     */
    private Optional<RecipePathMatch> getArtifactPaths(String groupId, String artifactId, String version,
            Set<Path> seenRedirects) {
        Path groupPath = handleRedirect(this.baseDirectory.resolve(groupId.replace('.', File.separatorChar)), seenRedirects,
                RecipePathMatch::getGroup, groupId, artifactId, version);
        Path artifactFolder = groupPath.resolve(ARTIFACT);
        Path artifactPath = handleRedirect(artifactFolder.resolve(artifactId), seenRedirects, RecipePathMatch::getArtifact,
                groupId, artifactId, version);
        Path versionFolder = groupPath.resolve(VERSION);
        Path versionPath = handleRedirect(versionFolder.resolve(version), seenRedirects, RecipePathMatch::getVersion, groupId,
                artifactId, version);
        Path artifactAndVersionPath = handleRedirect(artifactPath.resolve(VERSION).resolve(version), seenRedirects,
                RecipePathMatch::getArtifactAndVersion, groupId, artifactId, version);
        if (!Files.exists(groupPath)) {
            return Optional.empty();
        }
        return Optional.of(new RecipePathMatch(groupPath,
                Files.exists(artifactPath) ? artifactPath : null,
                Files.exists(versionPath) ? versionPath : null,
                Files.exists(artifactAndVersionPath) ? artifactAndVersionPath : null,
                !Files.exists(artifactFolder) && !Files.exists(versionFolder)));
    }

    /**
     * handles redirections, which can be used to point a directory to another artifacts directory
     * <p>
     * This can be very useful to point submodules of a build to the parent module
     */
    private Path handleRedirect(Path original, Set<Path> seenRedirects, Function<RecipePathMatch, Path> mapping, String groupId,
            String artifactId, String version) {
        Path redirect = original.resolve(REDIRECT_YAML);
        if (!Files.exists(redirect)) {
            return original;
        }
        if (seenRedirects.contains(original)) {
            throw new RuntimeException("Redirect loop detected, path: " + seenRedirects);
        }
        String toRedirect = null;
        try (InputStream in = Files.newInputStream(redirect)) {

            Map<String, Object> contents = MAPPER.readValue(in, Map.class);
            if (contents == null) {
                log.log(Level.SEVERE, "Failed to read " + redirect.toAbsolutePath() + " location was missing");
                return original;
            }
            if (contents.containsKey(GROUP_ID)) {
                groupId = (String) contents.get(GROUP_ID);
            }
            if (contents.containsKey(ARTIFACT_ID)) {
                artifactId = (String) contents.get(ARTIFACT_ID);
            }
            if (contents.containsKey(VERSION_ID)) {
                version = (String) contents.get(VERSION_ID);
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to read " + redirect.toAbsolutePath(), e);
            return original;
        }
        Optional<RecipePathMatch> redirected = getArtifactPaths(groupId, artifactId, version, seenRedirects);
        if (redirected.isEmpty()) {
            log.log(Level.SEVERE, "Redirect in " + redirect.toAbsolutePath() + " did not resolve to an directory");
            return original;
        }
        return mapping.apply(redirected.get());
    }

    @Override
    public <T> void writeArtifactData(AddRecipeRequest<T> data) {
        Set<Path> seenRedirects = new HashSet<>();
        String groupId = data.getGroupId();
        String artifactId = data.getArtifactId();
        String version = data.getVersion();
        Path resolved = handleRedirect(this.baseDirectory.resolve(groupId.replace('.', File.separatorChar)), seenRedirects,
                RecipePathMatch::getGroup, groupId, artifactId, version);
        if (artifactId != null) {
            resolved = resolved.resolve(ARTIFACT);
            resolved = handleRedirect(resolved.resolve(artifactId), seenRedirects, RecipePathMatch::getArtifact,
                    groupId, artifactId, version);
        }
        if (version != null) {
            resolved = resolved.resolve(VERSION);
            resolved = handleRedirect(resolved.resolve(version), seenRedirects, RecipePathMatch::getVersion, groupId,
                    artifactId, version);
        }
        try {
            Files.createDirectories(resolved);
            data.getRecipe().getHandler().write(data.getData(), resolved.resolve(data.getRecipe().getName()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

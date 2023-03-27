package com.redhat.hacbs.recipies.location;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;

/**
 * Entry point for requesting build information
 */
public class RecipeGroupManager {

    private static final Logger log = Logger.getLogger(RecipeGroupManager.class.getName());

    /**
     * The repositories, the highest priority first
     */
    private final List<RecipeDirectory> repositories;

    public RecipeGroupManager(List<RecipeDirectory> repositories) {
        this.repositories = repositories;
    }

    public ArtifactInfoResponse requestArtifactInformation(ArtifactInfoRequest request) {

        Map<String, Map<BuildRecipe, Path>> groupAuthoritativeResults = new HashMap<>();
        Map<GAV, Map<BuildRecipe, Path>> results = new HashMap<>();
        for (var buildLocationRequest : request.getRequests()) {
            var group = buildLocationRequest.getGroupId();
            var authoritative = groupAuthoritativeResults.get(group);
            log.warnf("Looking up %s", group);

            if (authoritative != null) {
                results.put(buildLocationRequest, authoritative);
            } else {
                List<RecipePathMatch> paths = new ArrayList<>();
                //minor optimisation, if a path is group authoritative then we don't need to look for various sub paths
                List<RecipePathMatch> nonGroupAuthPaths = new ArrayList<>();
                //we need to do a lookup
                for (var r : repositories) {
                    var possible = r.getArtifactPaths(buildLocationRequest.getGroupId(), buildLocationRequest.getArtifactId(),
                            buildLocationRequest.getVersion());
                    if (possible.isPresent()) {
                        paths.add(possible.get());
                        if (!possible.get().isGroupAuthoritative()) {
                            nonGroupAuthPaths.add(possible.get());
                        }
                    }
                }
                Map<BuildRecipe, Path> possibleAuthoritative = new HashMap<>();
                Map<BuildRecipe, Path> buildResults = new HashMap<>();
                boolean authPossible = true;
                for (var recipe : request.getRecipeFiles()) {
                    boolean found = false;
                    for (var path : nonGroupAuthPaths) {
                        if (path.getArtifactAndVersion() != null) {
                            //if there is a file specific to this group, artifact and version it takes priority
                            Path resolvedPath = path.getArtifactAndVersion().resolve(recipe.getName());
                            log.warnf("Searching for recipe in %s for specific path for GAV", resolvedPath);
                            if (Files.exists(resolvedPath)) {
                                authPossible = false;
                                buildResults.put(recipe, resolvedPath);
                                found = true;
                            }
                        }
                    }
                    if (!found) {
                        for (var path : nonGroupAuthPaths) {
                            //then version specific
                            if (path.getVersion() != null) {
                                Path resolvedPath = path.getVersion().resolve(recipe.getName());
                                log.warnf("Searching for recipe in %s for version", resolvedPath);
                                if (Files.exists(resolvedPath)) {
                                    authPossible = false;
                                    buildResults.put(recipe, resolvedPath);
                                    found = true;
                                }
                            }
                        }
                        if (!found) {
                            //then artifact specific
                            for (var path : nonGroupAuthPaths) {
                                if (path.getArtifact() != null) {
                                    Path resolvedPath = path.getArtifact().resolve(recipe.getName());
                                    log.warnf("Searching for recipe in %s for artifactId", resolvedPath);
                                    if (Files.exists(resolvedPath)) {
                                        authPossible = false;
                                        buildResults.put(recipe, resolvedPath);
                                        found = true;
                                    }
                                }
                            }
                            if (!found) {
                                //then group id specific, which should be the most common
                                for (var path : paths) {
                                    Path resolvedPath = path.getGroup().resolve(recipe.getName());
                                    log.warnf("Searching for recipe in %s for groupId", resolvedPath);
                                    if (Files.exists(resolvedPath)) {
                                        buildResults.put(recipe, resolvedPath);
                                        if (path.isGroupAuthoritative()) {
                                            possibleAuthoritative.put(recipe, resolvedPath);
                                        } else {
                                            authPossible = false;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (authPossible) {
                    groupAuthoritativeResults.put(buildLocationRequest.getGroupId(), possibleAuthoritative);
                }
                results.put(buildLocationRequest, buildResults);
            }
        }
        return new ArtifactInfoResponse(results);
    }

    public BuildInfoResponse requestBuildInformation(BuildInfoRequest buildInfoRequest) {

        String scmUri = normalizeScmUri(buildInfoRequest.getScmUri());

        List<Path> paths = new ArrayList<>();
        for (var r : repositories) {
            var possible = r.getBuildPaths(scmUri, buildInfoRequest.getVersion());
            if (possible.isPresent()) {
                paths.add(possible.get());
            }
        }

        Map<BuildRecipe, Path> buildResults = new HashMap<>();
        for (var recipe : buildInfoRequest.getRecipeFiles()) {
            for (var path : paths) {
                var option = path.resolve(recipe.getName());
                if (Files.exists(option)) {
                    buildResults.put(recipe, option);
                    break;
                }
            }

        }
        return new BuildInfoResponse(buildResults);
    }

    public static String normalizeScmUri(String scmUri) {
        if (scmUri.endsWith(".git")) {
            scmUri = scmUri.substring(0, scmUri.length() - 4);
        }
        int pos = scmUri.indexOf("://");
        if (pos != -1) {
            scmUri = scmUri.substring(pos + 3);
        }
        return scmUri;
    }
}

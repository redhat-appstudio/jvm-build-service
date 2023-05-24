package com.redhat.hacbs.artifactcache.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Singleton;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;

/**
 * The cache implementation, this acts as a normal client
 */
@Singleton
@Startup
public class CacheFacade {

    final Map<String, BuildPolicy> buildPolicies;
    final Map<String, List<RepositoryCache>> buildPolicyCaches;

    public CacheFacade(Map<String, BuildPolicy> buildPolicies) throws Exception {
        this.buildPolicies = buildPolicies;
        this.buildPolicyCaches = new HashMap<>();

        for (var e : buildPolicies.entrySet()) {
            List<RepositoryCache> cacheList = new ArrayList<>(e.getValue().getRepositories());
            buildPolicyCaches.put(e.getKey(), cacheList);
        }
    }

    public Optional<ArtifactResult> getArtifactFile(String buildPolicy, String group, String artifact, String version,
            String target, boolean tracked) {
        //first attempt we only look for cached files, so no network access
        for (var i : buildPolicyCaches.get(buildPolicy)) {
            try {
                var res = i.getArtifactFile(group, artifact, version, target, tracked, false);
                if (res.isPresent()) {
                    return res;
                }
            } catch (Throwable t) {
                Log.errorf(t, "Unable to download %s:%s:%s", group, artifact, target);
            }
        }
        return Optional.empty();
    }

    public Optional<Map<String, String>> getArtifactMetadata(String buildPolicy, String group, String artifact, String version,
            String target, boolean tracked) {
        //first attempt we only look for cached files, so no network access
        List<RepositoryCache> cacheList = buildPolicyCaches.get(buildPolicy);
        for (var i : cacheList) {
            try {
                var res = i.getArtifactFile(group, artifact, version, target, tracked, true);
                if (res.isPresent()) {
                    return res.map(ArtifactResult::getMetadata);
                }
            } catch (Throwable t) {
                Log.errorf(t, "Unable to download %s:%s:%s", group, artifact, target);
            }
        }
        for (var i : cacheList) {
            try {
                var res = i.getArtifactFile(group, artifact, version, target, tracked, false);
                if (res.isPresent()) {
                    return res.map(ArtifactResult::getMetadata);
                }
            } catch (Throwable t) {
                Log.errorf(t, "Unable to download %s:%s:%s", group, artifact, target);
            }
        }
        return Optional.empty();
    }

    public List<ArtifactResult> getMetadataFiles(String buildPolicy, String group, String target) {
        List<ArtifactResult> results = new ArrayList<>();
        for (var i : buildPolicyCaches.get(buildPolicy)) {
            var res = i.getMetadataFile(group, target);
            if (res.isPresent()) {
                results.add(res.get());
            }
        }
        return results;
    }

}

package com.redhat.hacbs.artifactcache.services;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;

/**
 * The cache implementation, this acts as a normal client
 */
@Singleton
@Startup
public class CacheFacade {

    final Path path;
    final Map<String, BuildPolicy> buildPolicies;
    final Map<String, List<RepositoryCache>> buildPolicyCaches;
    final Map<String, RepositoryCache> caches;

    public CacheFacade(@ConfigProperty(name = "cache-path") Path path,
            Map<String, BuildPolicy> buildPolicies) throws Exception {
        this.path = path;
        Log.infof("Creating cache with path %s", path.toAbsolutePath());
        //TODO: we don't actually use this at the moment
        this.buildPolicies = buildPolicies;
        this.caches = new HashMap<>();
        this.buildPolicyCaches = new HashMap<>();

        for (var e : buildPolicies.entrySet()) {
            List<RepositoryCache> cacheList = new ArrayList<>();
            for (var repository : e.getValue().getRepositories()) {
                if (!caches.containsKey(repository.getName())) {
                    caches.put(repository.getName(), new RepositoryCache(path.resolve(repository.getName()), repository));
                }
                cacheList.add(caches.get(repository.getName()));
            }
            buildPolicyCaches.put(e.getKey(), cacheList);
        }
    }

    public Optional<ArtifactResult> getArtifactFile(String buildPolicy, String group, String artifact, String version,
            String target, boolean tracked) {
        for (var i : buildPolicyCaches.get(buildPolicy)) {
            try {
                var res = i.getArtifactFile(group, artifact, version, target, tracked);
                if (res.isPresent()) {
                    return res;
                }
            } catch (Throwable t) {
                Log.errorf(t, "Unable to download %s:%s:%s", group, artifact, target);
            }
        }
        return Optional.empty();
    }

    public Optional<ArtifactResult> getMetadataFile(String buildPolicy, String group, String target) {
        for (var i : buildPolicyCaches.get(buildPolicy)) {
            var res = i.getMetadataFile(group, target);
            if (res.isPresent()) {
                return res;
            }
        }
        return Optional.empty();
    }

}

package com.redhat.hacbs.artifactcache.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;

/**
 * Class that consumes the repository config and creates the runtime representation of the repositories
 */
class BuildPolicyManager {

    public static final String STORE_LIST = ".store-list";
    public static final String BUILD_POLICY = "build-policy.";

    final StorageManager storageManager;

    final RemoteRepositoryManager remoteRepositoryManager;

    BuildPolicyManager(StorageManager storageManager, RemoteRepositoryManager remoteRepositoryManager) {
        this.storageManager = storageManager;
        this.remoteRepositoryManager = remoteRepositoryManager;
    }

    @Produces
    @Singleton
    Map<String, BuildPolicy> createBuildPolicies(@ConfigProperty(name = "build-policies") Set<String> buildPolicies,
            Config config) {

        Map<String, BuildPolicy> ret = new HashMap<>();

        for (String policy : buildPolicies) {
            Optional<String> stores = config.getOptionalValue(BUILD_POLICY + policy + STORE_LIST, String.class);
            if (stores.isEmpty()) {
                Log.warnf("No config for build policy %s, ignoring", policy);
                continue;
            }
            List<RepositoryCache> repositories = new ArrayList<>();
            for (var store : stores.get().split(",")) {
                var cache = remoteRepositoryManager.getRemoteRepositories(store);
                repositories.addAll(cache);
            }
            if (!repositories.isEmpty()) {
                ret.put(policy, new BuildPolicy(repositories));
            } else {
                Log.warnf("No configured repositories for build policy %s, ignoring", policy);
            }
        }
        if (ret.isEmpty()) {
            throw new IllegalStateException("No configured build policies present, repository cache cannot function");
        }
        return ret;
    }

}

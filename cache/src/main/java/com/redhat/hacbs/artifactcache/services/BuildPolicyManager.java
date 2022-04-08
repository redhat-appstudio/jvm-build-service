package com.redhat.hacbs.artifactcache.services;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.enterprise.inject.Produces;
import javax.inject.Singleton;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.artifactcache.services.mavenclient.MavenClient;

import io.quarkus.logging.Log;

/**
 * Class that consumes the repository config and creates the runtime representation of the repositories
 */
class BuildPolicyManager {

    private static final String STORE = "store.";
    private static final String URL = ".url";
    private static final String TYPE = ".type";
    public static final String STORE_LIST = ".store-list";
    public static final String BUILD_POLICY = "build-policy.";

    @Produces
    @Singleton
    Map<String, BuildPolicy> createBuildPolicies(@ConfigProperty(name = "build-policies") Set<String> buildPolicies,
            Config config) {
        Map<String, BuildPolicy> ret = new HashMap<>();
        Map<String, Repository> remoteStores = new HashMap<>();
        for (String policy : buildPolicies) {
            Optional<String> stores = config.getOptionalValue(BUILD_POLICY + policy + STORE_LIST, String.class);
            if (stores.isEmpty()) {
                Log.warnf("No config for build policy %s, ignoring", policy);
                continue;
            }
            List<Repository> repositories = new ArrayList<>();
            for (var store : stores.get().split(",")) {
                Repository existing = remoteStores.get(store);
                if (existing != null) {
                    repositories.add(existing);
                } else {
                    existing = createRepository(config, store);
                    if (existing != null) {
                        repositories.add(existing);
                        remoteStores.put(store, existing);
                    }
                }
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

    private Repository createRepository(Config config, String repo) {
        Optional<URI> uri = config.getOptionalValue(STORE + repo + URL, URI.class);
        Optional<RepositoryType> optType = config.getOptionalValue(STORE + repo + TYPE, RepositoryType.class);
        if (uri.isPresent()) {
            Log.infof("Repository %s added with URI %s", repo, uri.get());
            RepositoryType type = optType.orElse(RepositoryType.MAVEN2);
            RepositoryClient client;
            switch (type) {
                case MAVEN2:
                    //TODO: custom SSL config for internal certs
                    client = MavenClient.of(repo, uri.get());
                    break;
                case S3:
                    client = new RepositoryClient() {
                        @Override
                        public Optional<RepositoryResult> getArtifactFile(String buildPolicy, String group, String artifact,
                                String version,
                                String target) {
                            throw new RuntimeException("NOT IMPLEMENTED YET");
                        }

                        @Override
                        public Optional<RepositoryResult> getMetadataFile(String buildPolicy, String group, String target) {
                            throw new RuntimeException("NOT IMPLEMENTED YET");
                        }
                    };
                    break;
                default:
                    throw new RuntimeException("Unknown type: " + type);
            }
            return new Repository(repo, uri.get(), type, client);
        } else {
            Log.warnf("Repository %s was listed but has no config and will be ignored", repo);
            return null;
        }
    }
}

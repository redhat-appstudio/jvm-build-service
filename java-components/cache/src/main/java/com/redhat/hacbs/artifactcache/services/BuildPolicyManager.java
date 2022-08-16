package com.redhat.hacbs.artifactcache.services;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.artifactcache.artifactwatch.RebuiltArtifacts;
import com.redhat.hacbs.artifactcache.services.client.maven.MavenClient;
import com.redhat.hacbs.artifactcache.services.client.ociregistry.OCIRegistryRepositoryClient;
import com.redhat.hacbs.artifactcache.services.client.s3.S3RepositoryClient;

import io.quarkus.logging.Log;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Class that consumes the repository config and creates the runtime representation of the repositories
 */
class BuildPolicyManager {

    private static final String STORE = "store.";
    private static final String URL = ".url";
    private static final String TYPE = ".type";
    private static final String BUCKET = ".bucket";
    private static final String REGISTRY = ".registry";
    private static final String TOKEN = ".token";
    private static final String PREPEND_TAG = ".prepend-tag";
    private static final String REPOSITORY = ".repository";
    private static final String OWNER = ".owner";
    private static final String INSECURE = ".insecure";
    private static final String PREFIXES = ".prefixes";
    public static final String STORE_LIST = ".store-list";
    public static final String BUILD_POLICY = "build-policy.";
    public static final String ARTIFACT_DEPLOYMENTS = "artifact-deployments";

    @Inject
    S3Client s3Client;

    @Inject
    RebuiltArtifacts rebuiltArtifacts;

    @Produces
    @Singleton
    Map<String, BuildPolicy> createBuildPolicies(@ConfigProperty(name = "build-policies") Set<String> buildPolicies,
            Config config) {

        Map<String, BuildPolicy> ret = new HashMap<>();
        Map<String, Repository> remoteStores = new HashMap<>();
        //TODO: this is a bit of a hack
        //we read the deployment config and if present use it to configure the 'rebuilt' repo
        var registryOwner = config.getOptionalValue("registry.owner", String.class);
        if (registryOwner.isPresent()) {
            var host = config.getOptionalValue("registry.host", String.class).orElse("quay.io");
            var port = config.getOptionalValue("registry.port", int.class).orElse(443);
            var token = config.getOptionalValue("registry" + TOKEN, String.class);
            var repository = config.getOptionalValue("registry" + REPOSITORY, String.class).orElse(ARTIFACT_DEPLOYMENTS);
            var insecure = config.getOptionalValue("registry" + INSECURE, boolean.class).orElse(false);
            var prependTag = config.getOptionalValue("registry" + PREPEND_TAG, String.class);

            remoteStores.put("rebuilt",
                    new Repository("rebuilt",
                            "http" + (insecure ? "" : "s") + "://" + host + ":" + port + "/" + registryOwner.get() + "/"
                                    + repository,
                            RepositoryType.OCI_REGISTRY,
                            new OCIRegistryRepositoryClient(host, registryOwner.get(), repository, token, prependTag,
                                    insecure, rebuiltArtifacts)));
        }

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

        if (uri.isPresent() && optType.orElse(RepositoryType.MAVEN2) == RepositoryType.MAVEN2) {
            Log.infof("Maven repository %s added with URI %s", repo, uri.get());
            RepositoryClient client = MavenClient.of(repo, uri.get());
            return new Repository(repo, uri.get().toASCIIString(), RepositoryType.MAVEN2, client);
        } else if (optType.orElse(null) == RepositoryType.S3) {
            //we need to get the config

            Optional<String> bucket = config.getOptionalValue(STORE + repo + BUCKET, String.class);
            if (bucket.isPresent()) {
                //make sure the bucket is present
                //TODO: permissions of buckets created this way?
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket.get()).build());
                String[] prefixes = config.getOptionalValue(STORE + repo + PREFIXES, String.class).orElse("default").split(",");
                RepositoryClient client = new S3RepositoryClient(s3Client, Arrays.asList(prefixes), bucket.get());
                Log.infof("S3 repository %s added with bucket %s and prefixes %s", repo, bucket.get(),
                        Arrays.toString(prefixes));
                return new Repository(repo, "s3://" + bucket + Arrays.toString(prefixes), RepositoryType.S3, client);
            } else {
                Log.warnf("S3 Repository %s was listed but has no bucket configured and will be ignored", repo);
            }
        } else if (optType.orElse(null) == RepositoryType.OCI_REGISTRY) {
            String registry = config.getOptionalValue(STORE + repo + REGISTRY, String.class).orElse("quay.io");
            Optional<String> owner = config.getOptionalValue(STORE + repo + OWNER, String.class);
            Optional<String> token = config.getOptionalValue(STORE + repo + TOKEN, String.class);
            Optional<String> prependTag = config.getOptionalValue(STORE + repo + PREPEND_TAG, String.class);
            String repository = config.getOptionalValue(STORE + repo + REPOSITORY, String.class).orElse(ARTIFACT_DEPLOYMENTS);
            if (owner.isPresent()) {
                boolean enableHttpAndInsecureFailover = config.getOptionalValue(STORE + repo + INSECURE, Boolean.class)
                        .orElse(Boolean.FALSE);
                String u = owner.get();

                RepositoryClient client = new OCIRegistryRepositoryClient(registry, u, repository, token, prependTag,
                        enableHttpAndInsecureFailover, rebuiltArtifacts);
                Log.infof("OCI registry %s added with owner %s", registry, u);
                return new Repository(repo, "oci://" + registry + "/" + u, RepositoryType.OCI_REGISTRY, client);
            } else {
                Log.warnf("OCI registry %s was listed but has no owner configured and will be ignored", repo);
            }
        }
        Log.warnf("Repository %s was listed but has no config and will be ignored", repo);
        return null;
    }
}

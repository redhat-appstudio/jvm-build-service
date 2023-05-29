package com.redhat.hacbs.artifactcache.services;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.microprofile.config.Config;

import com.redhat.hacbs.artifactcache.artifactwatch.RebuiltArtifacts;
import com.redhat.hacbs.artifactcache.services.client.maven.MavenClient;
import com.redhat.hacbs.artifactcache.services.client.ociregistry.OCIRegistryRepositoryClient;
import com.redhat.hacbs.artifactcache.services.client.s3.S3RepositoryClient;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Startup
@Singleton
public class RemoteRepositoryManager {

    public static final String SYSTEM = "system.";
    private static final String STORE = "store.";
    private static final String URL = ".url";
    private static final String TYPE = ".type";
    private static final String BUCKET = ".bucket";
    private static final String REGISTRY = ".registry";
    private static final String OWNER = ".owner";
    private static final String PREFIXES = ".prefixes";

    private static final String TOKEN = ".token";
    private static final String PREPEND_TAG = ".prepend-tag";
    private static final String REPOSITORY = ".repository";
    private static final String INSECURE = ".insecure";
    public static final String ARTIFACT_DEPLOYMENTS = "artifact-deployments";
    private final ConcurrentHashMap<String, List<RepositoryCache>> remoteStores = new ConcurrentHashMap<>();

    @Inject
    S3Client s3Client;

    @Inject
    Config config;

    @Inject
    StorageManager storageManager;
    @Inject
    RebuiltArtifacts rebuiltArtifacts;

    @Inject
    RecipeManager recipeManager;

    @PostConstruct
    void setup() throws IOException, GitAPIException {
        var registryOwner = config.getOptionalValue("registry.owner", String.class);
        if (registryOwner.isPresent()) {
            var host = config.getOptionalValue("registry.host", String.class).orElse("quay.io");
            var port = config.getOptionalValue("registry.port", int.class).orElse(443);
            var token = config.getOptionalValue("registry" + TOKEN, String.class);
            var repository = config.getOptionalValue("registry" + REPOSITORY, String.class).orElse(ARTIFACT_DEPLOYMENTS);
            var insecure = config.getOptionalValue("registry" + INSECURE, boolean.class).orElse(false);
            var prependTag = config.getOptionalValue("registry" + PREPEND_TAG, String.class);

            Repository rebuiltRepo = new Repository("rebuilt",
                    "http" + (insecure ? "" : "s") + "://" + host + ":" + port + "/" + registryOwner.get() + "/"
                            + repository,
                    RepositoryType.OCI_REGISTRY,
                    new OCIRegistryRepositoryClient(host + ":" + port, registryOwner.get(), repository, token, prependTag,
                            insecure, rebuiltArtifacts, storageManager));
            remoteStores.put("rebuilt", List.of(new RepositoryCache(storageManager.resolve("rebuilt"), rebuiltRepo, false)));
        }
    }

    public List<RepositoryCache> getRemoteRepositories(String name) {
        var store = remoteStores.get(name);
        if (store == null) {
            synchronized (remoteStores) {
                store = remoteStores.get(name);
                if (store == null) {
                    var repo = createRepository(name);
                    store = new ArrayList<>();
                    if (repo != null) {
                        for (var i : repo) {
                            store.add(new RepositoryCache(storageManager.resolve(i.getName()), i, true));
                        }
                    }
                    remoteStores.put(name, store);
                }
            }
        }
        return store;
    }

    private List<Repository> createRepository(String repo) {
        Repository existingSystemRepo = null;
        if (repo.startsWith(SYSTEM)) {
            return createSystemRepository(repo.substring(SYSTEM.length()));
        } else {
            List<Repository> systemRepos = createSystemRepository(repo);
            if (systemRepos.size() == 1) {
                existingSystemRepo = systemRepos.get(0);
            }
        }

        Optional<URI> uri = config.getOptionalValue(STORE + repo + URL, URI.class);
        Optional<RepositoryType> optType = config.getOptionalValue(STORE + repo + TYPE, RepositoryType.class);

        if (uri.isPresent() && optType.orElse(RepositoryType.MAVEN2) == RepositoryType.MAVEN2) {
            if (existingSystemRepo != null && existingSystemRepo.getUri().equals(uri.get().toASCIIString())) {
                Log.infof("Maven repository %s added with URI %s, matching system repo of same name", repo, uri.get());
                return List.of(existingSystemRepo);
            }
            Log.infof("Maven repository %s added with URI %s", repo, uri.get());
            RepositoryClient client = MavenClient.of(repo, uri.get());
            return List.of(new Repository(repo, uri.get().toASCIIString(), RepositoryType.MAVEN2, client));
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
                return List.of(new Repository(repo, "s3://" + bucket + Arrays.toString(prefixes), RepositoryType.S3, client));
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
                        enableHttpAndInsecureFailover, rebuiltArtifacts, storageManager);
                Log.infof("OCI registry %s added with owner %s", registry, u);
                return List.of(new Repository(repo, "oci://" + registry + "/" + u, RepositoryType.OCI_REGISTRY, client));
            } else {
                Log.warnf("OCI registry %s was listed but has no owner configured and will be ignored", repo);
            }
        }
        Log.warnf("Repository %s was listed but has no config and will be ignored", repo);
        return null;
    }

    private List<Repository> createSystemRepository(String repo) {
        List<Repository> ret = new ArrayList<>();
        for (var info : recipeManager.getRepositoryInfo(repo)) {
            try {
                if (info.getUri() != null && !info.getUri().isBlank()) {
                    Log.infof("System Maven repository %s added with URI %s", repo, info.getUri());
                    RepositoryClient client = MavenClient.of(repo, new URI(info.getUri()));
                    ret.add(new Repository(repo, info.getUri(), RepositoryType.MAVEN2, client));
                }
                if (info.getRepositories() != null) {
                    for (var i : info.getRepositories()) {
                        ret.addAll(createSystemRepository(i));
                    }
                }
            } catch (Exception e) {
                Log.errorf("Failed to load system repository " + repo + " from " + info);
            }
        }
        if (ret.isEmpty()) {
            Log.warnf("No system repository %s found.", repo);
        }
        return ret;
    }

}

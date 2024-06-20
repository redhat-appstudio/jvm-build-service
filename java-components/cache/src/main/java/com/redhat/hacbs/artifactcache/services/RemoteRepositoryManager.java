package com.redhat.hacbs.artifactcache.services;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.Config;

import com.redhat.hacbs.artifactcache.artifactwatch.RebuiltArtifacts;
import com.redhat.hacbs.artifactcache.services.client.maven.MavenClient;
import com.redhat.hacbs.artifactcache.services.client.ociregistry.OCIRepositoryClient;
import com.redhat.hacbs.resources.model.v1alpha1.jbsconfigstatus.ImageRegistry;
import com.redhat.hacbs.resources.util.RegistryUtil;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;

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
    private static final String HACBS = "hacbs";
    private final ConcurrentHashMap<String, List<RepositoryCache>> remoteStores = new ConcurrentHashMap<>();

    @Inject
    Config config;

    @Inject
    StorageManager storageManager;
    @Inject
    RebuiltArtifacts rebuiltArtifacts;

    @Inject
    RecipeManager recipeManager;

    StorageManager hacbsStorageMgr;
    private RepositoryCache rebuiltCache;

    @PostConstruct
    void setup() throws URISyntaxException {
        hacbsStorageMgr = storageManager.resolve(HACBS);
        //TODO: this is a bit of a hack
        //we read the deployment config and if present use it to configure the 'rebuilt' repo
        var registryOwner = config.getOptionalValue("registry.owner", String.class);
        var mavenRepo = config.getOptionalValue("maven.repository.url", String.class);
        var mavenUsername = config.getOptionalValue("maven.repository.username", String.class);
        var mavenPassword = config.getOptionalValue("maven.repository.password", String.class);
        if (mavenRepo.isPresent()) {
            //if we are deploying to a maven repo we use this by default
            Repository rebuiltRepo = new Repository("rebuilt",
                    mavenRepo.get(),
                    RepositoryType.MAVEN2,
                    new MavenClient("rebuilt", new URI(mavenRepo.get()), 1, mavenUsername.orElse(null),
                            mavenPassword.orElse(null)));
            rebuiltCache = new RepositoryCache(storageManager.resolve("rebuilt"), rebuiltRepo, false);
            remoteStores.put("rebuilt", List.of(rebuiltCache));

        } else if (registryOwner.isPresent()) {
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
                    new OCIRepositoryClient(host + (port == 443 ? "" : ":" + port), registryOwner.get(), repository,
                            token, prependTag,
                            insecure, rebuiltArtifacts, hacbsStorageMgr));
            rebuiltCache = new RepositoryCache(storageManager.resolve("rebuilt"), rebuiltRepo, false);
            remoteStores.put("rebuilt", List.of(rebuiltCache));
        }
        var sharedRegistries = config.getOptionalValue("shared.registries", String.class);
        // We have a semicolon separated set of potential registries.
        if (sharedRegistries.isPresent()) {
            String[] registries = sharedRegistries.get().split(";", -1);
            for (int i = 0; i < registries.length; i++) {
                ImageRegistry registry = RegistryUtil.parseRegistry(registries[i]);
                String name = "shared-rebuilt-" + i;

                Repository rebuiltRepo = new Repository(name,
                        "http" + (registry.getInsecure() ? "" : "s") + "://" +
                                registry.getHost() + ":" + registry.getPort() + "/" +
                                registry.getRepository() + "/"
                                + registry.getPrependTag(),
                        RepositoryType.OCI_REGISTRY,
                        new OCIRepositoryClient(registry.getHost() + (registry.getPort().equals("443")
                                ? ""
                                : ":" + registry.getPort()), registry.getOwner(),
                                registry.getRepository(),
                                // TODO: How to pass token through
                                Optional.empty(),
                                Optional.of(registry.getPrependTag()),
                                registry.getInsecure(),
                                rebuiltArtifacts,
                                hacbsStorageMgr));

                remoteStores.put(name,
                        List.of(new RepositoryCache(storageManager.resolve(name), rebuiltRepo, false)));
            }
        }
        rebuiltArtifacts.addImageDeletionListener(new RebuiltArtifacts.RebuiltArtifactDeletionListener() {
            private static final String DIGEST_PREFIX = "sha256:";

            @Override
            public void rebuiltArtifactDeleted(String gav, String imageDigest) {
                try {
                    String digestHash = imageDigest.substring(DIGEST_PREFIX.length());
                    Log.infof("Deleting cached image with digest %s", digestHash);
                    hacbsStorageMgr.delete(digestHash);
                    rebuiltCache.deleteGav(gav);
                } catch (Exception e) {
                    Log.errorf(e, "Failed to clear cache path for image %s", imageDigest);
                }
            }
        });
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

                RepositoryClient client = new OCIRepositoryClient(registry, u, repository, token, prependTag,
                        enableHttpAndInsecureFailover, rebuiltArtifacts, hacbsStorageMgr);
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

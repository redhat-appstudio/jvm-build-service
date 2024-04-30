package com.redhat.hacbs.artifactcache.services.client.ociregistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import com.redhat.hacbs.artifactcache.artifactwatch.RebuiltArtifacts;
import com.redhat.hacbs.artifactcache.services.ArtifactResult;
import com.redhat.hacbs.artifactcache.services.RepositoryClient;
import com.redhat.hacbs.artifactcache.services.StorageManager;
import com.redhat.hacbs.common.images.ociclient.LocalImage;
import com.redhat.hacbs.common.images.ociclient.OCIRegistryClient;
import com.redhat.hacbs.resources.util.ShaUtil;

import io.quarkus.logging.Log;

public class OCIRepositoryClient implements RepositoryClient {

    private final Optional<String> prependHashedGav;
    private final StorageManager storageManager;

    private final OCIRegistryClient registryClient;

    final RebuiltArtifacts rebuiltArtifacts;

    final Map<String, CountDownLatch> locks = new ConcurrentHashMap<>();

    public OCIRepositoryClient(String registry, String owner, String repository, Optional<String> authToken,
            Optional<String> prependHashedGav,
            boolean enableHttpAndInsecureFailover, RebuiltArtifacts rebuiltArtifacts,
            StorageManager storageManager) {
        this.prependHashedGav = prependHashedGav;
        this.registryClient = new OCIRegistryClient(registry, owner, repository, authToken, enableHttpAndInsecureFailover);
        this.rebuiltArtifacts = rebuiltArtifacts;
        this.storageManager = storageManager;
    }

    @Override
    public String getName() {
        return registryClient.getName();
    }

    @Override
    public Optional<ArtifactResult> getArtifactFile(String group, String artifact, String version,
            String target) {
        long time = System.currentTimeMillis();

        group = group.replace("/", ".");
        String groupPath = group.replace(DOT, File.separator);
        String hashedGav = ShaUtil.sha256sum(group, artifact, version);
        if (prependHashedGav.isPresent()) {
            hashedGav = prependHashedGav.get() + UNDERSCORE + hashedGav;
        }
        if (hashedGav.length() > 128) {
            hashedGav = hashedGav.substring(0, 128);
        }

        String gav = group + ":" + artifact + ":" + version;
        if (!rebuiltArtifacts.isPossiblyRebuilt(gav)) {
            return Optional.empty();
        }
        Log.debugf("Attempting to retrieve %s for artifact %s", hashedGav, gav);
        return doDownload(group, artifact, version, target, time, groupPath, hashedGav);
    }

    private Optional<ArtifactResult> doDownload(String group, String artifact, String version, String target, long time,
            String groupPath, String hashedGav) {
        try {
            var image = registryClient.pullImage(hashedGav);
            if (image.isEmpty()) {
                return Optional.empty();
            }

            Optional<Path> repoRoot = getLocalCachePath(image.get());
            if (repoRoot.isPresent()) {
                Path fileWeAreAfter = repoRoot.get().resolve(groupPath).resolve(artifact).resolve(version).resolve(target);

                boolean exists = Files.exists(fileWeAreAfter);
                if (exists) {
                    return Optional.of(
                            new ArtifactResult(null, Files.newInputStream(fileWeAreAfter), Files.size(fileWeAreAfter),
                                    getSha1(fileWeAreAfter),
                                    Map.of()));
                } else {
                    Log.warnf("Key %s:%s:%s not found", group, artifact, version);
                }
            }
        } catch (Exception ioe) {
            throw new RuntimeException(ioe);
        } finally {
            Log.debugf("OCI registry request to %s:%s:%s took %sms", group, artifact, version,
                    System.currentTimeMillis() - time);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ArtifactResult> getMetadataFile(String group, String target) {
        return Optional.empty();
    }

    private Optional<Path> getLocalCachePath(LocalImage image)
            throws IOException {
        String digestHash = image.getDigestHash();
        Path digestHashPath = storageManager.accessDirectory(digestHash);
        Path artifactsPath = Paths.get(digestHashPath.toString(), ARTIFACTS);
        if (existInLocalCache(digestHashPath)) {
            return Optional.of(artifactsPath);
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            var existing = locks.putIfAbsent(digestHash, latch);
            if (existing == null) {
                try {
                    return pullFromRemoteAndCache(image, digestHashPath);
                } finally {
                    latch.countDown();
                    locks.remove(digestHash);
                }
            } else {
                try {
                    existing.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                if (existInLocalCache(digestHashPath)) {
                    return Optional.of(artifactsPath);
                }
                return Optional.empty();
            }
        }
    }

    private Optional<Path> pullFromRemoteAndCache(LocalImage image,
            Path digestHashPath)
            throws IOException {
        //layer 2 is artifacts, should be a 3 layer image
        //we don't actually check as we might want to allow more,
        //and just require the artifacts to be in the last layer
        Path outputPath = Files.createDirectories(digestHashPath);

        image.pullLayer(image.getLayerCount() - 1, outputPath);
        return Optional.of(Paths.get(outputPath.toString(), ARTIFACTS));
    }

    private boolean existInLocalCache(Path digestHashPath) {
        return Files.exists(digestHashPath) && Files.isDirectory(digestHashPath)
                && Files.exists(digestHashPath.resolve(ARTIFACTS));
    }

    private Optional<String> getSha1(Path file) throws IOException {
        Path shaFile = Paths.get(file.toString() + DOT + SHA_1);
        boolean exists = Files.exists(shaFile);
        if (exists) {
            return Optional.of(Files.readString(shaFile));
        }
        return Optional.empty();
    }

    private static final String UNDERSCORE = "_";
    private static final String ARTIFACTS = "artifacts";
    private static final String DOT = ".";
    private static final String SHA_1 = "sha1";
}

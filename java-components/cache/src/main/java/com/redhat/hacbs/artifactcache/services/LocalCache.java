package com.redhat.hacbs.artifactcache.services;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import javax.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.ClientWebApplicationException;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;

/**
 * The cache implementation, this acts as a normal client
 */
@Singleton
@Startup
public class LocalCache implements RepositoryClient {

    public static final String CACHEMISS = ".cachemiss";
    public static final String DOWNLOADS = ".downloads";
    final Path path;
    final Map<String, BuildPolicy> buildPolicies;

    /**
     * Tracks in progress downloads to prevent concurrency issues
     */
    final ConcurrentMap<String, DownloadingFile> inProgress = new ConcurrentHashMap<>();

    public LocalCache(@ConfigProperty(name = "cache-path") Path path,
            Map<String, BuildPolicy> buildPolicies) throws Exception {
        this.path = path;
        Log.infof("Creating cache with path %s", path.toAbsolutePath());
        //TODO: we don't actually use this at the moment
        this.buildPolicies = buildPolicies;
        for (var e : buildPolicies.entrySet()) {
            for (var repository : e.getValue().getRepositories()) {
                Path repoPath = path.resolve(repository.getName());
                Files.createDirectories(repoPath);
                Files.createDirectories(repoPath.resolve(DOWNLOADS));
            }
        }
    }

    @Override
    public Optional<RepositoryResult> getArtifactFile(String buildPolicy, String group, String artifact, String version,
            String target, Long buildStartTime) {
        //TODO: we don't really care about the policy when using standard maven repositories
        String targetFile = group.replaceAll("\\.", File.separator) + File.separator + artifact
                + File.separator + version + File.separator + target;
        return handleFile(buildPolicy, targetFile,
                (c) -> c.getArtifactFile(buildPolicy, group, artifact, version, target, buildStartTime));
    }

    private Optional<RepositoryResult> handleFile(String buildPolicy, String gavBasedTarget,
            Function<RepositoryClient, Optional<RepositoryResult>> clientInvocation) {
        BuildPolicy policy = buildPolicies.get(buildPolicy);
        if (policy == null) {
            return Optional.empty();
        }
        try {
            for (var repo : policy.getRepositories()) {
                try {
                    String targetFile;
                    //for S3 we will have a separate cache per build policy name
                    if (repo.getType().isBuildPolicyUsed()) {
                        targetFile = buildPolicy + File.separator + gavBasedTarget;
                    } else {
                        targetFile = gavBasedTarget;
                    }
                    String targetMissingFile = targetFile + CACHEMISS;
                    String repoTarget = repo.getName() + File.separator + targetFile;
                    var check = inProgress.get(repoTarget);
                    if (check != null) {
                        check.awaitReady();
                    }
                    Map<String, String> metadata;
                    if (repo.getType() == RepositoryType.MAVEN2) {
                        metadata = Map.of("maven-repo", repo.getName());
                    } else {
                        metadata = Map.of();
                    }

                    Path actual = path.resolve(repoTarget);
                    if (Files.exists(actual)) {
                        //we need to double check, there is a small window for a race here
                        //it should not matter as we do an atomic move, but better to be safe
                        check = inProgress.get(repoTarget);
                        if (check != null) {
                            check.awaitReady();
                        }
                        return Optional.of(
                                new RepositoryResult(Files.newInputStream(actual), Files.size(actual), Optional.empty(),
                                        metadata));
                    }
                    //now we check for the missing file marker
                    //                    String missingFileMarker = repo.getName() + File.separator + targetMissingFile;
                    //                    Path missing = path.resolve(missingFileMarker);
                    //                    if (!Files.exists(missing)) {
                    DownloadingFile newFile = new DownloadingFile(targetFile);
                    var existing = inProgress.putIfAbsent(targetFile, newFile);
                    if (existing != null) {
                        //another thread is downloading this
                        existing.awaitReady();
                        //the result may have been a miss, so we need to check the file is there
                        if (Files.exists(actual)) {
                            return Optional.of(new RepositoryResult(Files.newInputStream(actual), Files.size(actual),
                                    Optional.empty(), metadata));
                        }
                    } else {
                        Optional<RepositoryResult> result = newFile.download(clientInvocation, repo.getClient(), actual,
                                null, path.resolve(repo.getName()).resolve(DOWNLOADS), repo.getType());
                        if (result.isPresent()) {
                            return Optional.of(new RepositoryResult(result.get().getData(), result.get().getSize(),
                                    result.get().getExpectedSha(), metadata));
                        }
                    }
                    //}
                } catch (Exception e) {
                    Log.errorf(e, "Failed to download %s from %s", gavBasedTarget, repo.getUri());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<RepositoryResult> getMetadataFile(String buildPolicy, String group, String target) {
        String targetFile = buildPolicy + File.separator + group.replaceAll("\\.", File.separator) + File.separator + target;
        return handleFile(buildPolicy, targetFile, (c) -> c.getMetadataFile(buildPolicy, group, target));
    }

    /**
     * Represents a file that is in the process of being downloaded into the cache
     */
    final class DownloadingFile {

        final String key;
        boolean ready = false;

        DownloadingFile(String key) {
            this.key = key;
        }

        void awaitReady() {
            synchronized (this) {
                while (!ready) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        Optional<RepositoryResult> download(Function<RepositoryClient, Optional<RepositoryResult>> clientInvocation,
                RepositoryClient repositoryClient,
                Path downloadTarget,
                Path missingFileMarker,
                Path downloadTempDir,
                RepositoryType repositoryType) {
            try {
                Optional<RepositoryResult> result = clientInvocation.apply(repositoryClient);
                if (!repositoryType.shouldIgnoreLocalCache()) {
                    if (result.isPresent()) {
                        MessageDigest md = MessageDigest.getInstance("SHA-1");
                        Path tempFile = Files.createTempFile(downloadTempDir, "download", ".part");
                        try (InputStream in = result.get().getData(); OutputStream out = Files.newOutputStream(tempFile)) {
                            byte[] buffer = new byte[1024];
                            int r;
                            while ((r = in.read(buffer)) > 0) {
                                out.write(buffer, 0, r);
                                md.update(buffer, 0, r);
                            }
                        }
                        if (result.get().getExpectedSha().isPresent()) {
                            byte[] digest = md.digest();
                            StringBuilder sb = new StringBuilder(40);
                            for (int i = 0; i < digest.length; ++i) {
                                sb.append(Integer.toHexString((digest[i] & 0xFF) | 0x100).substring(1, 3));
                            }
                            if (!sb.toString().equalsIgnoreCase(result.get().getExpectedSha().get())) {
                                //TODO: handle this better
                                Log.error("Filed to cache " + downloadTarget + " calculated sha '" + sb.toString()
                                        + "' did not match expected '" + result.get().getExpectedSha().get() + "'");
                                return clientInvocation.apply(repositoryClient);
                            }
                        }

                        Files.createDirectories(downloadTarget.getParent());
                        Files.move(tempFile, downloadTarget, StandardCopyOption.ATOMIC_MOVE);
                        return Optional.of(new RepositoryResult(Files.newInputStream(downloadTarget), result.get().getSize(),
                                result.get().getExpectedSha(), result.get().getMetadata()));
                    } else if (missingFileMarker != null) {
                        Files.createDirectories(missingFileMarker.getParent());
                        Files.createFile(missingFileMarker);
                        return Optional.empty();
                    }
                    return Optional.empty();
                } else {
                    return result;
                }
            } catch (ClientWebApplicationException e) {
                if (e.getResponse().getStatus() == 404) {
                    return Optional.empty();
                }
                Log.errorf(e, "Failed to download artifact %s from %s", downloadTarget, repositoryClient);
                return Optional.empty();
            } catch (Exception e) {
                Log.errorf(e, "Failed to download artifact %s from %s", downloadTarget, repositoryClient);
                return Optional.empty();
            } finally {
                inProgress.remove(key);
                synchronized (this) {
                    ready = true;
                    notifyAll();
                }
            }
        }
    }

}

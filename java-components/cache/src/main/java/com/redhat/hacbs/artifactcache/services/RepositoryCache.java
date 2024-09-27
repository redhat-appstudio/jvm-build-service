package com.redhat.hacbs.artifactcache.services;

import static com.redhat.hacbs.classfile.tracker.TrackingData.extractClassifier;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.HashingOutputStream;
import com.redhat.hacbs.classfile.tracker.TrackingData;
import com.redhat.hacbs.common.maven.GAV;

import io.quarkus.logging.Log;

/**
 * A per repository cache implementation
 */
public class RepositoryCache {

    private static final Object DELETE_IN_PROGRESS = new Object();
    public static final String SHA_1 = ".sha1";
    public static final String DOWNLOADS = ".downloads";
    public static final String HEADERS = ".hacbs-http-headers";
    public static final String ORIGINAL = "original";
    public static final String TRANSFORMED = "transformed";
    final StorageManager storageManager;
    final StorageManager downloaded;
    final StorageManager transformed;
    final StorageManager tempDownloads;
    final Repository repository;

    final boolean overwriteExistingBytecodeMarkers;

    /**
     * Tracks in progress downloads to prevent concurrency issues
     */
    final ConcurrentMap<String, DownloadingFile> inProgressDownloads = new ConcurrentHashMap<>();
    final ConcurrentMap<String, CountDownLatch> inProgressTransformations = new ConcurrentHashMap<>();

    /**
     * This will either hold {@link #DELETE_IN_PROGRESS} if delete is in progress, or a countdown latch.
     *
     * Access must be synchronized on the map itself
     */
    final Map<String, Object> inUseTracker = new HashMap<>();

    public RepositoryCache(StorageManager storageManager, Repository repository, boolean overwriteExistingBytecodeMarkers) {
        this.storageManager = storageManager;
        this.downloaded = storageManager.resolve(ORIGINAL);
        this.transformed = storageManager.resolve(TRANSFORMED);
        this.tempDownloads = storageManager.resolve(DOWNLOADS);
        this.repository = repository;
        this.overwriteExistingBytecodeMarkers = overwriteExistingBytecodeMarkers;
        Log.infof("Creating cache with path %s", storageManager.toString());
    }

    public Repository getRepository() {
        return repository;
    }

    public Optional<ArtifactResult> getArtifactFile(String group, String artifact, String version, String target,
            boolean tracked, boolean cacheOnly) {
        if (tracked && target.endsWith(".jar.sha1")) {
            var jarResult = getArtifactFile(group, artifact, version, target.substring(0, target.length() - ".sha1".length()),
                    tracked, cacheOnly);
            if (jarResult.isEmpty()) {
                return Optional.empty();
            }
            Optional<String> expectedSha = jarResult.get().getExpectedSha();
            if (expectedSha.isEmpty()) {
                return Optional.empty();
            }
            byte[] bytes = expectedSha.get().getBytes(StandardCharsets.UTF_8);
            try {
                jarResult.get().close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return Optional
                    .of(new ArtifactResult(null, new ByteArrayInputStream(bytes), bytes.length, Optional.empty(), Map.of()));

        } else {
            //TODO: we don't really care about the policy when using standard maven repositories
            String targetFile = group.replace('.', File.separatorChar) + File.separator + artifact
                    + File.separator + version + File.separator + target;
            return handleFile(targetFile, group.replace(File.separatorChar, '.') + ":" + artifact + ":" + version,
                    (c) -> c.getArtifactFile(group, artifact, version, target), tracked, cacheOnly,
                    extractClassifier(artifact, version, target));
        }
    }

    public Optional<ArtifactResult> getMetadataFile(String group, String target) {
        try {
            return repository.getClient().getMetadataFile(group, target);
        } catch (Exception e) {
            Log.debugf(e, "Failed to metadata %s/%s from %s", group, target, repository.getUri());
            return Optional.empty();
        }
    }

    private Optional<ArtifactResult> handleFile(String targetFile, String gav,
            Function<RepositoryClient, Optional<ArtifactResult>> clientInvocation, boolean tracked, boolean cacheOnly,
            String classifier) {
        try {
            var check = inProgressDownloads.get(targetFile);
            if (check != null) {
                check.awaitReady();
            }
            Path actual = downloaded.accessFile(targetFile);
            Path trackedFile = transformed.accessFile(targetFile);
            if (Files.exists(actual)) {
                //we need to double check, there is a small window for a race here
                //it should not matter as we do an atomic move, but better to be safe
                check = inProgressDownloads.get(targetFile);
                if (check != null) {
                    check.awaitReady();
                }
                return handleDownloadedFile(actual, trackedFile, tracked, gav, classifier);
            }
            if (cacheOnly) {
                return Optional.empty();
            }
            DownloadingFile newFile = new DownloadingFile(targetFile);
            var existing = inProgressDownloads.putIfAbsent(targetFile, newFile);
            while (existing != null) {
                //another thread is downloading this
                existing.awaitReady();
                //the result may have been a miss, so we need to check the file is there
                //if the file is not there it may mean that the sha1 was wrong
                //so we never cache it
                if (Files.exists(actual)) {
                    return handleDownloadedFile(actual, trackedFile, tracked, gav, classifier);
                }
                existing = inProgressDownloads.putIfAbsent(targetFile, newFile);
            }
            return newFile.download(clientInvocation, repository.getClient(), actual, trackedFile,
                    tempDownloads, tracked, gav, classifier);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteGav(String gav) {
        while (true) {
            synchronized (inUseTracker) {
                Object res = inUseTracker.get(gav);
                if (res == DELETE_IN_PROGRESS) {
                    //already deleting
                    return;
                } else if (res != null) {
                    try {
                        inUseTracker.wait();
                        continue;
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                inUseTracker.put(gav, DELETE_IN_PROGRESS);
                break;
            }
        }
        try {
            var parsed = GAV.parse(gav);
            String targetFile = parsed.getGroupId().replaceAll("\\.", File.separator) + File.separator + parsed.getArtifactId()
                    + File.separator + parsed.getVersion();
            storageManager.delete(targetFile);
        } finally {
            synchronized (inUseTracker) {
                inUseTracker.remove(gav);
                inUseTracker.notifyAll();
            }
        }

    }

    private Optional<ArtifactResult> handleDownloadedFile(Path downloaded, Path trackedFileTarget, boolean tracked, String gav,
            String classifier)
            throws IOException, InterruptedException {
        var lock = new GavLock(gav);
        try {

            boolean jarFile = downloaded.toString().endsWith(".jar");
            //same headers for both
            String fileName = downloaded.getFileName().toString();
            Path headers = downloaded.getParent().resolve(fileName + HEADERS);
            Path originalSha1 = downloaded.getParent().resolve(fileName + SHA_1);
            Map<String, String> headerMap = new HashMap<>();
            if (Files.exists(headers)) {
                try (InputStream in = Files.newInputStream(headers)) {
                    Properties p = new Properties();
                    p.load(in);
                    for (var i : p.entrySet()) {
                        headerMap.put(i.getKey().toString().toLowerCase(), i.getValue().toString());
                    }
                }
            }
            if (!jarFile || !tracked) {
                String sha = null;
                if (Files.exists(originalSha1)) {
                    sha = Files.readString(originalSha1, StandardCharsets.UTF_8);
                }
                return Optional
                        .of(new ArtifactResult(downloaded, Files.newInputStream(downloaded), Files.size(downloaded),
                                Optional.ofNullable(sha),
                                headerMap, lock));
            }

            Path instrumentedSha;
            Path trackedJarFile;
            if (jarFile) {
                instrumentedSha = trackedFileTarget.getParent().resolve(fileName + SHA_1);
                trackedJarFile = trackedFileTarget;
            } else {
                instrumentedSha = trackedFileTarget;
                trackedJarFile = trackedFileTarget.getParent()
                        .resolve(fileName.substring(0, fileName.length() - SHA_1.length()));
            }
            CountDownLatch existing = inProgressTransformations.get(gav);
            if (existing != null) {
                existing.await();
            }
            if (!Files.exists(trackedJarFile)) {
                CountDownLatch myLatch = new CountDownLatch(1);
                existing = inProgressTransformations.putIfAbsent(gav, myLatch);
                if (existing != null) {
                    existing.await();
                } else {
                    Files.createDirectories(trackedJarFile.getParent());
                    try (OutputStream out = Files.newOutputStream(trackedJarFile); var in = Files.newInputStream(downloaded)) {
                        HashingOutputStream hashingOutputStream = new HashingOutputStream(out);
                        Map<String, String> attributes = StringUtils.isNotBlank(classifier) ? Map.of("classifier", classifier)
                                : Map.of();
                        ClassFileTracker.addTrackingDataToJar(in,
                                new TrackingData(gav, repository.getName(), attributes),
                                hashingOutputStream,
                                overwriteExistingBytecodeMarkers);
                        hashingOutputStream.close();

                        Files.writeString(instrumentedSha, hashingOutputStream.getHash());
                    } catch (Throwable e) {
                        Log.errorf(e, "Failed to track jar %s", downloaded);
                        Files.delete(trackedJarFile);
                    } finally {
                        myLatch.countDown();
                        inProgressTransformations.remove(gav);
                    }
                }
            }
            if (Files.exists(trackedJarFile)) {
                if (jarFile) {
                    String sha = null;
                    if (Files.exists(instrumentedSha)) {
                        sha = Files.readString(instrumentedSha, StandardCharsets.UTF_8);
                    }
                    return Optional
                            .of(new ArtifactResult(trackedJarFile, Files.newInputStream(trackedJarFile),
                                    Files.size(trackedJarFile),
                                    Optional.ofNullable(sha), headerMap, lock));
                } else {
                    return Optional
                            .of(new ArtifactResult(instrumentedSha, Files.newInputStream(instrumentedSha),
                                    Files.size(instrumentedSha),
                                    Optional.empty(), Map.of(), lock));
                }
            }

            String sha = null;
            if (Files.exists(originalSha1)) {
                sha = Files.readString(originalSha1, StandardCharsets.UTF_8);
            }
            return Optional
                    .of(new ArtifactResult(downloaded, Files.newInputStream(downloaded), Files.size(downloaded),
                            Optional.ofNullable(sha),
                            headerMap, lock));
        } catch (IOException | RuntimeException | InterruptedException t) {
            lock.run();
            throw t;
        } catch (Throwable t) {
            lock.run();
            throw new RuntimeException(t);
        }
    }

    /**
     * Represents a file that is in the process of being downloaded into the cache
     */
    final class DownloadingFile {

        final String key;
        boolean ready = false;
        Throwable problem;

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
                if (problem != null) {
                    throw new RuntimeException(problem);
                }
            }
        }

        Optional<ArtifactResult> download(Function<RepositoryClient, Optional<ArtifactResult>> clientInvocation,
                RepositoryClient repositoryClient,
                Path downloadTarget,
                Path trackedFile,
                StorageManager downloadTempDir,
                boolean tracked,
                String gav,
                String classifier) {
            GavLock lock = new GavLock(gav);
            try {
                Optional<ArtifactResult> result = clientInvocation.apply(repositoryClient);
                if (result.isPresent()) {
                    MessageDigest md = MessageDigest.getInstance("SHA-1");
                    Path tempFile = Files.createTempFile(downloadTempDir.accessDirectory("downloads"), "download", ".part");
                    InputStream in = result.get().getData();
                    try (OutputStream out = Files.newOutputStream(tempFile)) {
                        byte[] buffer = new byte[1024];
                        int r;
                        while ((r = in.read(buffer)) > 0) {
                            out.write(buffer, 0, r);
                            md.update(buffer, 0, r);
                        }
                    } finally {
                        try {
                            in.close();
                        } catch (IOException e) {
                            Log.errorf(e, "Failed to close HTTP stream");
                        }
                    }
                    if (result.get().getExpectedSha().isPresent()) {
                        byte[] digest = md.digest();
                        StringBuilder sb = new StringBuilder(40);
                        for (int i = 0; i < digest.length; ++i) {
                            sb.append(Integer.toHexString((digest[i] & 0xFF) | 0x100).substring(1, 3));
                        }
                        String hash = sb.toString();

                        if (!hash.equalsIgnoreCase(result.get().getExpectedSha().get())) {
                            Log.error("Filed to cache " + downloadTarget + " from " + repositoryClient.getName()
                                    + " calculated sha '" + hash
                                    + "' did not match expected '" + result.get().getExpectedSha().get() + "'");
                            if (tracked) {
                                Path tempTransformedFile = Files.createTempFile(downloadTempDir.accessDirectory("downloads"),
                                        "transformed", ".part");
                                try (var inFromFile = Files.newInputStream(tempFile);
                                        var transformedOut = Files.newOutputStream(tempTransformedFile)) {
                                    Map<String, String> attributes = StringUtils.isNotBlank(classifier)
                                            ? Map.of("classifier", classifier)
                                            : Map.of();
                                    ClassFileTracker.addTrackingDataToJar(inFromFile,
                                            new TrackingData(gav, repository.getName(), attributes),
                                            transformedOut, overwriteExistingBytecodeMarkers);
                                }
                                Files.delete(tempFile);
                                return Optional
                                        .of(new ArtifactResult(tempTransformedFile, Files.newInputStream(tempTransformedFile),
                                                Files.size(tempTransformedFile),
                                                Optional.empty(), result.get().getMetadata(), () -> {
                                                    try {
                                                        Files.delete(tempTransformedFile);
                                                    } catch (IOException e) {
                                                        throw new RuntimeException(e);
                                                    }
                                                }, lock));

                            } else {
                                return Optional
                                        .of(new ArtifactResult(tempFile, Files.newInputStream(tempFile), Files.size(tempFile),
                                                Optional.empty(), result.get().getMetadata(), () -> {
                                                    try {
                                                        Files.delete(tempFile);
                                                    } catch (IOException e) {
                                                        throw new RuntimeException(e);
                                                    }
                                                }, lock));
                            }
                        }
                    }

                    Files.createDirectories(downloadTarget.getParent());
                    Files.move(tempFile, downloadTarget, StandardCopyOption.ATOMIC_MOVE);

                    if (result.get().getExpectedSha().isPresent()) {
                        Files.writeString(downloadTarget.getParent().resolve(downloadTarget.getFileName().toString() + SHA_1),
                                result.get().getExpectedSha().get(), StandardCharsets.UTF_8);
                    }
                    Properties p = new Properties();
                    for (var e : result.get().getMetadata().entrySet()) {
                        p.put(e.getKey().toLowerCase(), e.getValue());
                    }
                    p.remove("content-length"); //use the actual on disk length
                    try (OutputStream out = Files.newOutputStream(
                            downloadTarget.getParent().resolve(downloadTarget.getFileName().toString() + HEADERS))) {
                        p.store(out, "");
                    }
                    return handleDownloadedFile(downloadTarget, trackedFile, tracked, gav, classifier);
                }
                return Optional.empty();
            } catch (Throwable e) {
                lock.run();
                synchronized (this) {
                    problem = e;
                }
                Log.errorf(e, "Failed to download artifact %s from %s", downloadTarget, repositoryClient);
                return Optional.empty();
            } finally {
                inProgressDownloads.remove(key);
                synchronized (this) {
                    ready = true;
                    notifyAll();
                }
            }
        }
    }

    class GavLock implements Runnable {
        final String gav;
        boolean closed;

        GavLock(String gav) {
            this.gav = gav;
            while (true) {
                synchronized (inUseTracker) {
                    Object results = inUseTracker.get(gav);
                    if (results == DELETE_IN_PROGRESS) {
                        try {
                            inUseTracker.wait();
                            continue;
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    } else if (results == null) {
                        results = new AtomicInteger();
                        inUseTracker.put(gav, results);
                    }
                    AtomicInteger count = (AtomicInteger) results;
                    count.incrementAndGet();
                    break;
                }
            }
        }

        @Override
        public void run() {
            synchronized (inUseTracker) {
                if (closed) {
                    return;
                }
                closed = true;
                AtomicInteger count = (AtomicInteger) inUseTracker.get(gav);
                if (count.decrementAndGet() == 0) {
                    inUseTracker.remove(gav);
                    inUseTracker.notifyAll();
                }
            }
        }
    }

}

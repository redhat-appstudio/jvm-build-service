package com.redhat.hacbs.artifactcache.services;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.noop.NoopCounter;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ExecutorRecorder;

/**
 * Manager class that deals with freeing up disk space if the disk usage gets too high.
 * <p>
 * It uses a read write lock to make sure that nothing is accessing files in the process of being deleted.
 * <p>
 * This basically means that the cache will pause for a short period of time while entries are cleaned.
 */
@Singleton
public class RootStorageManager implements StorageManager {

    private static final String MARKER = "cache.directory.marker";
    public static final int DELETE_TIMEOUT = 10000;

    final Path path;

    private static final long DELETE_IN_PROGRESS = -1;
    private final int deleteBatchSize;

    /**
     * Lock map used to manage timestamps.
     */
    final ConcurrentMap<String, AtomicLong> inUseMap = new ConcurrentHashMap<>();

    private static final Function<String, AtomicLong> FACTORY = (s) -> new AtomicLong(System.currentTimeMillis());
    private final FileStore fileStore;
    private final long highWaterFreeSpace;
    private final long lowWaterFreeSpace;

    private Timer timer;

    private final Counter cacheFreeCount;
    private final Counter deletedEntries;

    @Inject
    public RootStorageManager(
            @ConfigProperty(name = "cache-path") Path path,
            @ConfigProperty(name = "cache-disk-percentage-high-water") double highWater,
            @ConfigProperty(name = "cache-disk-percentage-low-water") double lowWater,
            @ConfigProperty(name = "cache-delete-batch-size", defaultValue = "30") int deleteBatchSize,
            MeterRegistry registry) throws IOException {
        this.path = path;
        Files.createDirectories(path);
        this.fileStore = Files.getFileStore(path);
        highWaterFreeSpace = (long) (fileStore.getTotalSpace() * (1 - highWater));
        lowWaterFreeSpace = (long) (fileStore.getTotalSpace() * (1 - lowWater));
        this.deleteBatchSize = deleteBatchSize;
        Log.infof("Cache requires at least %s space free, and will delete to the low water mark of %s. Total disk size is %s.",
                formatSize(highWaterFreeSpace),
                formatSize(lowWaterFreeSpace), formatSize(fileStore.getTotalSpace()));
        registry.gauge("free_disk_space", fileStore, new ToDoubleFunction<FileStore>() {
            @Override
            public double applyAsDouble(FileStore value) {
                try {
                    return value.getUnallocatedSpace();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        cacheFreeCount = registry.counter("cache_free_count");
        deletedEntries = registry.counter("cache_deleted_entries");
    }

    RootStorageManager(FileStore fileStore,
            Path path,
            double highWater,
            double lowWater,
            int deleteBatchSize) throws IOException {
        this.fileStore = fileStore;
        this.path = path;
        Files.createDirectories(path);
        highWaterFreeSpace = (long) (fileStore.getTotalSpace() * (1 - highWater));
        lowWaterFreeSpace = (long) (fileStore.getTotalSpace() * (1 - lowWater));
        this.deleteBatchSize = deleteBatchSize;
        Log.infof("Cache requires at least %s space free, and will delete to the low water mark of %s. Total disk size is %s.",
                formatSize(highWaterFreeSpace),
                formatSize(lowWaterFreeSpace), formatSize(fileStore.getTotalSpace()));
        cacheFreeCount = new NoopCounter(new Meter.Id("cache_free_count", Tags.empty(), null, null, Meter.Type.COUNTER));
        deletedEntries = new NoopCounter(new Meter.Id("cache_deleted_entries", Tags.empty(), null, null, Meter.Type.COUNTER));
    }

    @PostConstruct
    void setup() {
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                checkSpace();
            }
        }, 60000, 60000);
        ExecutorRecorder.getCurrent().execute(this::initialLoad);
    }

    @PreDestroy
    void destroy() {
        timer.cancel();
    }

    void initialLoad() {
        //there map be initial data in the cache dir, we load it into the in-memory map to allow it to be deleted
        //if the cache needs to be cleared
        AtomicInteger count = new AtomicInteger();
        try {

            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path marker = dir.resolve(MARKER);
                    if (Files.exists(marker)) {
                        count.incrementAndGet();
                        inUseMap.putIfAbsent(path.relativize(dir).toString(),
                                new AtomicLong(Files.getLastModifiedTime(dir).toMillis()));
                    }
                    return FileVisitResult.CONTINUE;
                }

            });

        } catch (IOException e) {
            Log.errorf("Failed to scan existing files", e);
        } finally {
            Log.infof("Initial load of existing entries completed, found %s", count.get());
        }

    }

    /**
     * Get access to a directory to store artifacts. This directory is considered a discrete unit, and if disk usage is too
     * high then this whole directory may be deleted at any point.
     *
     * @param relative The directory to access
     * @return An access token to access the directory
     */
    @Override
    public Path accessDirectory(String relative) throws IOException {
        checkRelative(relative);

        //deletion locks, if this is being deleted it is set to -1
        //otherwise we just use CAS to update it
        //deletion will notifyAll on the AtomicLong before it is removed
        long timeOut = System.currentTimeMillis() + DELETE_TIMEOUT;
        for (;;) {
            if (System.currentTimeMillis() > timeOut) {
                throw new IOException("Timed out waiting for entry deletion: " + relative);
            }
            AtomicLong current = inUseMap.computeIfAbsent(relative, FACTORY);
            long val = current.get();
            if (val == -1) {
                try {
                    current.wait(DELETE_TIMEOUT);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                if (current.compareAndSet(val, System.currentTimeMillis())) {
                    break;
                }
            }
        }

        Path dir = path.resolve(relative);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        } else if (!Files.isDirectory(dir)) {
            throw new RuntimeException("Not a directory");
        }
        Path marker = dir.resolve(MARKER);
        if (!Files.exists(marker)) {
            Files.writeString(marker, Long.toString(System.currentTimeMillis()));
        }
        return dir;

    }

    private void checkRelative(String relative) {
        if (relative.startsWith("/")) {
            throw new IllegalArgumentException("Path must not start with / :" + relative);
        }
        if (relative.endsWith("/")) {
            throw new IllegalArgumentException("Path must not end with / :" + relative);
        }
        if (relative.contains("//") || relative.contains("..") || relative.contains("./")) {
            throw new IllegalArgumentException("Path must not be in relative form :" + relative);
        }
    }

    @Override
    public Path accessFile(String relative) throws IOException {
        if (!relative.contains("/")) {
            throw new IllegalArgumentException("Cannot access files in the root of the storage manager: " + relative);
        }
        Path filePath = path.resolve(relative);
        Path dir = filePath.getParent();
        accessDirectory(path.relativize(dir).toString());
        return filePath;
    }

    @Override
    public StorageManager resolve(String relative) {
        checkRelative(relative);
        try {
            Files.createDirectories(path.resolve(relative));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new RelativeStorageManager(relative);
    }

    @Override
    public void delete(String relative) {
        checkRelative(relative);
        //note that this is not lock safe
        //this is only for explicit deletes
        //if this is called while the file
        //is being downloaded the result is undefined
        Path dir = path.resolve(relative);
        if (Files.exists(dir)) {
            safeDeleteRecursive(dir);
        }
        var existing = inUseMap.remove(relative);
        if (existing != null) {
            existing.set(System.currentTimeMillis());
            synchronized (existing) {
                existing.notifyAll();
            }
        }
    }

    @Override
    public String path() {
        return path.toAbsolutePath().toString();
    }

    @Override
    public void clear() {
        clear("");
    }

    void clear(String path) {
        var it = inUseMap.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().startsWith(path)) {
                delete(e.getKey());
            }
        }

        Log.infof("Clearing path %s", path);
        try (var s = Files.list(this.path.resolve(path))) {
            s.forEach(RootStorageManager::deleteRecursive);
        } catch (IOException e) {
            Log.errorf("Failed to clear path %s", e);
        } finally {
            Log.infof("Cache Free Completed");
            HashMap<String, AtomicLong> vals = new HashMap<>(inUseMap);
            inUseMap.clear();
            for (var i : vals.entrySet()) {
                i.getValue().set(1);
                synchronized (i.getValue()) {
                    i.getValue().notifyAll();
                }
            }
        }
    }

    void checkSpace() {
        try {
            if (fileStore.getUsableSpace() < highWaterFreeSpace) {
                freeSpace();
            }
        } catch (IOException e) {
            Log.errorf(e, "Failed to check cache space usage");
        }
    }

    private void freeSpace() throws IOException {
        Log.infof("Disk usage is too high, currently %s/%s is free, trying to delete entries to get this to %s",
                formatSize(fileStore.getUsableSpace()), formatSize(fileStore.getTotalSpace()), formatSize(lowWaterFreeSpace));
        cacheFreeCount.increment();
        try {
            TreeMap<Long, List<String>> entries = new TreeMap<>();
            for (var e : inUseMap.entrySet()) {
                entries.computeIfAbsent(e.getValue().get(), (s) -> new ArrayList<>()).add(e.getKey());
            }
            int batchCount = 0;
            var it = entries.entrySet().iterator();
            //free till we hit low water
            while (it.hasNext() && fileStore.getUsableSpace() < lowWaterFreeSpace) {
                Log.infof("Deleting batch %s of %s entries", batchCount++, deleteBatchSize);
                int count = 0;
                //delete in chunks of batch size
                while (count++ < deleteBatchSize && it.hasNext()) {
                    var toDel = it.next();
                    long expect = toDel.getKey();
                    for (var file : toDel.getValue()) {
                        AtomicLong lock = inUseMap.get(file);
                        if (lock == null) {
                            continue;
                        }
                        if (lock.compareAndSet(expect, DELETE_IN_PROGRESS)) {
                            inUseMap.remove(file);
                            try {
                                safeDeleteRecursive(path.resolve(file));
                            } catch (Exception e) {
                                Log.errorf(e, "Failed to clear %s", file);
                            } finally {
                                synchronized (lock) {
                                    lock.notifyAll();
                                }
                            }

                        } else {
                            Log.infof("Unable to delete %s as it is in use", file);
                        }
                    }
                }
            }
        } finally {
            Log.infof("Cache Free Completed");
        }
    }

    /**
     * Deletes a directory recursivly, but won't decend into sub directories with their own locks
     *
     * @param file
     */
    public static void safeDeleteRecursive(final Path file) {
        try {
            boolean canDelete = true;
            if (Files.isDirectory(file)) {
                try (Stream<Path> files = Files.list(file)) {
                    for (var f : files.toList()) {
                        if (Files.isDirectory(f)) {
                            if (Files.exists(f.resolve(MARKER))) {
                                canDelete = false;
                                continue;
                            }
                        }
                        safeDeleteRecursive(f);
                    }
                }
            }
            if (canDelete) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void deleteRecursive(final Path file) {
        try {
            if (Files.isDirectory(file)) {
                try (Stream<Path> files = Files.list(file)) {
                    for (var f : files.toList()) {
                        deleteRecursive(f);
                    }
                }
            }
            Files.delete(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    class RelativeStorageManager implements StorageManager {
        final String relativePath;

        RelativeStorageManager(String relativePath) {
            if (!relativePath.endsWith("/")) {
                relativePath = relativePath + "/";
            }
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            this.relativePath = relativePath;
        }

        @Override
        public Path accessDirectory(String relative) throws IOException {
            return RootStorageManager.this.accessDirectory(relativePath + relative);
        }

        @Override
        public Path accessFile(String relative) throws IOException {
            return RootStorageManager.this.accessFile(relativePath + relative);
        }

        @Override
        public StorageManager resolve(String relative) {
            return new RelativeStorageManager(relativePath + relative);
        }

        @Override
        public void delete(String relative) {
            RootStorageManager.this.delete(relativePath + relative);

        }

        @Override
        public String path() {
            return path.resolve(relativePath).toString();
        }

        @Override
        public void clear() {
            RootStorageManager.this.clear(relativePath);
        }

        @Override
        public String toString() {
            return "RelativeStorageManager{" +
                    "relativePath='" + relativePath + '\'' +
                    '}';
        }
    }

    /**
     * Turns bytes into a human readable format
     */
    public static String formatSize(long bytes) {
        int unit = 1024;
        if (bytes < unit)
            return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }
}

package com.redhat.hacbs.artifactcache.services;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;

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

    final Path path;
    final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final FileStore fileStore;
    private final long highWaterFreeSpace;
    private final long lowWaterFreeSpace;

    private Timer timer;

    @Inject
    public RootStorageManager(
            @ConfigProperty(name = "cache-path") Path path,
            @ConfigProperty(name = "cache-disk-percentage-high-water") double highWater,
            @ConfigProperty(name = "cache-disk-percentage-low-water") double lowWater) throws IOException {
        this.path = path;
        Files.createDirectories(path);
        this.fileStore = Files.getFileStore(path);
        highWaterFreeSpace = (long) (fileStore.getTotalSpace() * (1 - highWater));
        lowWaterFreeSpace = (long) (fileStore.getTotalSpace() * (1 - lowWater));
        Log.infof("Cache requires at least %s space free, and will delete to the low water mark of %s", highWaterFreeSpace,
                lowWaterFreeSpace);
    }

    RootStorageManager(FileStore fileStore,
            Path path,
            double highWater,
            double lowWater) throws IOException {
        this.fileStore = fileStore;
        this.path = path;
        Files.createDirectories(path);
        highWaterFreeSpace = (long) (fileStore.getTotalSpace() * (1 - highWater));
        lowWaterFreeSpace = (long) (fileStore.getTotalSpace() * (1 - lowWater));
        Log.infof("Cache requires at least %s space free, and will delete to the low water mark of %s", highWaterFreeSpace,
                lowWaterFreeSpace);
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
    }

    @PreDestroy
    void destroy() {
        timer.cancel();
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
        lock.readLock().lock();
        try {
            Path dir = path.resolve(relative);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            } else if (!Files.isDirectory(dir)) {
                throw new RuntimeException("Not a directory");
            }
            Files.writeString(dir.resolve(MARKER), Long.toString(System.currentTimeMillis()));
            return dir;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Path accessFile(String relative) throws IOException {
        if (!relative.contains("/")) {
            throw new IllegalArgumentException("Cannot access files in the root of the storage manager: " + relative);
        }
        lock.readLock().lock();
        try {
            Path filePath = path.resolve(relative);
            Path dir = filePath.getParent();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            } else if (!Files.isDirectory(dir)) {
                throw new RuntimeException("Not a directory");
            }
            Files.writeString(dir.resolve(MARKER), Long.toString(System.currentTimeMillis()));
            return filePath;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public StorageManager resolve(String relative) {
        try {
            Files.createDirectories(path.resolve(relative));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new RelativeStorageManager(relative);
    }

    @Override
    public void delete(String relative) {
        lock.readLock().lock();
        try {
            Path dir = path.resolve(relative);
            if (Files.exists(dir)) {
                deleteRecursive(dir);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String path() {
        return path.toAbsolutePath().toString();
    }

    @Override
    public void clear() {
        clear(path);
    }

    void clear(Path path) {

        Log.infof("Clearing path %s", path);
        lock.writeLock().lock();
        try (var s = Files.list(path)) {
            s.forEach(RootStorageManager::deleteRecursive);
        } catch (IOException e) {
            Log.errorf("Failed to clear path %s", e);
        } finally {
            lock.writeLock().unlock();
            Log.infof("Cache Free Completed");
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
        Log.infof("Disk usage is too high, freeing cache entries");
        lock.writeLock().lock();
        try {
            Deque<FreeEntry> stack = new ArrayDeque<>();
            TreeSet<FreeEntry> lastUsed = new TreeSet<>();
            Files.walkFileTree(path, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path marker = dir.resolve(MARKER);
                    if (Files.exists(marker)) {
                        String contents = Files.readString(marker);
                        long time = 0;
                        try {
                            time = Long.parseLong(contents);
                        } catch (Exception e) {
                            Log.errorf(e, "Failed to parse marker file %s", marker);
                        }
                        FreeEntry entry = new FreeEntry(time, dir);
                        lastUsed.add(entry);
                        stack.push(entry);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    FreeEntry current = stack.peek();
                    if (current != null) {
                        current.size += Files.size(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (Files.exists(dir.resolve(MARKER))) {
                        stack.pop();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            var it = lastUsed.iterator();
            while (fileStore.getUsableSpace() < lowWaterFreeSpace && it.hasNext()) {
                var entry = it.next();
                Log.infof("Removing %s", entry.path);
                deleteRecursive(entry.path);
            }

        } finally {
            lock.writeLock().unlock();
            Log.infof("Cache Free Completed");
        }
    }

    static class FreeEntry implements Comparable<FreeEntry> {
        final long lastUsed;
        long size;
        final Path path;

        FreeEntry(long lastUsed, Path path) {
            this.lastUsed = lastUsed;
            this.path = path;
        }

        @Override
        public int compareTo(RootStorageManager.FreeEntry o) {
            //compare the last used, otherwise use the path, so they are never equal
            int val = Long.compare(lastUsed, o.lastUsed);
            if (val == 0) {
                return path.compareTo(o.path);
            }
            return val;
        }
    }

    public static void deleteRecursive(final Path file) {
        try {
            if (Files.isDirectory(file)) {
                try (Stream<Path> files = Files.list(file)) {
                    files.forEach(RootStorageManager::deleteRecursive);
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
            RootStorageManager.this.clear(path);
        }

        @Override
        public String toString() {
            return "RelativeStorageManager{" +
                    "relativePath='" + relativePath + '\'' +
                    '}';
        }
    }

}

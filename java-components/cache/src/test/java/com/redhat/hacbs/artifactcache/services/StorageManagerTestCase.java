package com.redhat.hacbs.artifactcache.services;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StorageManagerTestCase {

    @Test
    public void testDeletion() throws IOException, InterruptedException {

        StringBuilder hundredAndOneBytes = new StringBuilder();
        for (var i = 0; i < 101; ++i) {
            hundredAndOneBytes.append("a");
        }
        Path path = Files.createTempDirectory("test");
        RootStorageManager manager = new RootStorageManager(new MockFileSystem(path, 1000L), path, 0.5, 0.4, 1);
        Path f1 = manager.accessDirectory("t1").resolve("f1");
        Thread.sleep(2);
        Path f2 = manager.accessDirectory("t2").resolve("f2");
        Thread.sleep(2);
        Path f3 = manager.accessDirectory("t3").resolve("f3");
        Thread.sleep(2);
        Path f4 = manager.accessFile("t4/a");
        Thread.sleep(2);
        Files.writeString(f1, hundredAndOneBytes.toString());
        Files.writeString(f2, hundredAndOneBytes.toString());
        Files.writeString(f3, hundredAndOneBytes.toString());
        Files.writeString(f4, hundredAndOneBytes.toString());
        manager.checkSpace();
        Assertions.assertTrue(Files.exists(f1));
        Assertions.assertTrue(Files.exists(f2));
        Assertions.assertTrue(Files.exists(f3));
        Assertions.assertTrue(Files.exists(f4));
        Path f5 = manager.accessFile("t5/a");
        Thread.sleep(2);
        Files.writeString(f5, hundredAndOneBytes.toString());
        Thread.sleep(2);
        manager.accessDirectory("t5/sub");
        manager.checkSpace();
        Assertions.assertFalse(Files.exists(f1));
        Assertions.assertFalse(Files.exists(f2));
        Assertions.assertTrue(Files.exists(f3));
        Assertions.assertTrue(Files.exists(f4));
        Assertions.assertTrue(Files.exists(f5));
        f1 = manager.accessDirectory("t1").resolve("f1");
        Thread.sleep(2);
        f2 = manager.accessDirectory("t2").resolve("f2");
        Thread.sleep(2);
        Files.writeString(f1, hundredAndOneBytes.toString());
        Files.writeString(f2, hundredAndOneBytes.toString());
        manager.accessDirectory("t3"); //access it will mark it recently used
        Thread.sleep(2);
        manager.checkSpace();
        Assertions.assertTrue(Files.exists(f1));
        Assertions.assertTrue(Files.exists(f2));
        Assertions.assertTrue(Files.exists(f3));
        Assertions.assertFalse(Files.exists(f4));
        Assertions.assertFalse(Files.exists(f5.resolve("a")));
        Assertions.assertTrue(Files.exists(f5.getParent().resolve("sub"))); //we have a sub path in here that is managed separately, so f5 will not get deleted

    }

    private static class MockFileSystem extends FileStore {

        final Path path;
        final long size;

        private MockFileSystem(Path path, long size) {
            this.path = path;
            this.size = size;
        }

        @Override
        public String name() {
            return null;
        }

        @Override
        public String type() {
            return null;
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public long getTotalSpace() throws IOException {
            return size;
        }

        @Override
        public long getUsableSpace() throws IOException {
            AtomicLong count = new AtomicLong();
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    count.getAndAdd(Files.size(file));
                    return super.visitFile(file, attrs);
                }
            });
            return size - count.get();
        }

        @Override
        public long getUnallocatedSpace() throws IOException {
            return 0;
        }

        @Override
        public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
            return false;
        }

        @Override
        public boolean supportsFileAttributeView(String name) {
            return false;
        }

        @Override
        public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
            return null;
        }

        @Override
        public Object getAttribute(String attribute) throws IOException {
            return null;
        }
    }
}

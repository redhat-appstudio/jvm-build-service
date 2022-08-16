package com.redhat.hacbs.artifactcache.services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.artifactcache.test.util.HashUtil;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class LocalCacheTest {

    RepositoryClient.RepositoryResult current;

    public final RepositoryClient MOCK_CLIENT = new RepositoryClient() {
        @Override
        public Optional<RepositoryResult> getArtifactFile(String buildPolicy, String group, String artifact, String version,
                String target, Long buildStartTime) {
            return Optional.ofNullable(current);
        }

        @Override
        public Optional<RepositoryResult> getMetadataFile(String buildPolicy, String group, String target) {
            return Optional.ofNullable(current);
        }
    };

    @Test
    public void testHashHandling() throws Exception {
        runTest((localCache -> {
            try {
                current = new RepositoryClient.RepositoryResult(
                        new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)), 4, Optional.of("wrong sha"),
                        Map.of());
                localCache.getArtifactFile("default", "test", "test", "1.0", "test.pom", null);
                Files.walkFileTree(localCache.path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().equals("test.pom")) {
                            Assertions.fail("Hash does not match");
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });

                //now try with the correct hash
                current = new RepositoryClient.RepositoryResult(
                        new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)), 4,
                        Optional.of(HashUtil.sha1("test")), Map.of());
                localCache.getArtifactFile("default", "test", "test", "1.0", "test.pom", null);
                AtomicReference<Path> cachedFile = new AtomicReference<>();
                Files.walkFileTree(localCache.path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().equals("test.pom")) {
                            cachedFile.set(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                if (cachedFile.get() == null) {
                    Assertions.fail("File was not cached");
                }
                String contents = Files.readString(cachedFile.get());
                Assertions.assertEquals("test", contents);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

    void runTest(Consumer<LocalCache> consumer) throws Exception {
        Path temp = Files.createTempDirectory("cache-test");
        try {
            LocalCache localCache = new LocalCache(temp,
                    Map.of("default", new BuildPolicy(
                            List.of(new Repository("test", "http://test.com", RepositoryType.MAVEN2, MOCK_CLIENT)))));

            consumer.accept(localCache);

        } finally {
            deleteRecursive(temp);
        }
    }

    static void deleteRecursive(final Path file) {
        try {
            if (Files.isDirectory(file)) {
                try (Stream<Path> files = Files.list(file)) {
                    files.forEach(LocalCacheTest::deleteRecursive);
                }
            }
            Files.delete(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}

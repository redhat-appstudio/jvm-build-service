package com.redhat.hacbs.artifactcache.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.function.BiConsumer;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.resources.util.HashUtil;

import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class LocalCacheTest {

    ArtifactResult current;

    public final RepositoryClient MOCK_CLIENT = new RepositoryClient() {
        @Override
        public String getName() {
            return "";
        }

        @Override
        public Optional<ArtifactResult> getArtifactFile(String group, String artifact, String version,
                String target) {
            return Optional.ofNullable(current);
        }

        @Override
        public Optional<ArtifactResult> getMetadataFile(String group, String target) {
            return Optional.ofNullable(current);
        }
    };

    @Test
    public void testHashHandling() throws Exception {
        runTest((localCache, path) -> {
            try {
                current = new ArtifactResult(
                        null, new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)), 4, Optional.of("wrong sha"),
                        Map.of());
                localCache.getArtifactFile("default", "test", "test", "1.0", "test.pom", false);
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().equals("test.pom")) {
                            Assertions.fail("Hash does not match");
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });

                //now try with the correct hash
                current = new ArtifactResult(
                        null, new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)), 4,
                        Optional.of(HashUtil.sha1("test")), Map.of());
                localCache.getArtifactFile("default", "test", "test", "1.0", "test.pom", false);
                AtomicReference<Path> cachedFile = new AtomicReference<>();
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
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
        });
    }

    @Test
    public void testHashRequestedFirstTrackedArtifact() throws Exception {
        runTest((localCache, path) -> {
            try {
                byte[] jarFile = createJarFile();
                current = new ArtifactResult(
                        null, new ByteArrayInputStream(jarFile), jarFile.length, Optional.of(HashUtil.sha1(jarFile)),
                        Map.of());
                localCache.getArtifactFile("default", "test", "test", "1.0", "test.jar.sha1", true);
                var result = localCache.getArtifactFile("default", "test", "test", "1.0", "test.jar", true);
                try (ZipInputStream in = new ZipInputStream(result.get().getData())) {
                    var entry = in.getNextEntry();
                    boolean found = false;
                    while (entry != null) {
                        System.out.println(entry);
                        if (entry.getName().equals(getClass().getName().replace(".", "/") + ".class")) {
                            found = true;
                        }
                        entry = in.getNextEntry();
                    }
                    Assertions.assertTrue(found);
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    void runTest(BiConsumer<CacheFacade, Path> consumer) throws Exception {
        Path temp = Files.createTempDirectory("cache-test");
        try {
            RootStorageManager storageManager = new RootStorageManager(temp, 1, 1, 1, new CompositeMeterRegistry());
            CacheFacade localCache = new CacheFacade(
                    Map.of("default", new BuildPolicy(
                            List.of(new RepositoryCache(storageManager,
                                    new Repository("test", "http://test.com", RepositoryType.MAVEN2, MOCK_CLIENT), true)))));

            consumer.accept(localCache, temp);

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

    byte[] createJarFile() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos);
        jar.putNextEntry(new ZipEntry(getClass().getName().replace(".", "/") + ".class"));
        jar.write(getClass().getResourceAsStream(getClass().getSimpleName() + ".class").readAllBytes());
        jar.close();
        return baos.toByteArray();
    }

}

package com.redhat.hacbs.artifactcache.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.redhat.hacbs.artifactcache.ContainerRegistryTestResourceManager;
import com.redhat.hacbs.artifactcache.artifactwatch.RebuiltArtifacts;
import com.redhat.hacbs.resources.util.HashUtil;

import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTestResource(value = ContainerRegistryTestResourceManager.class, restrictToAnnotatedClass = true)
@QuarkusTest
public class ContainerRegistryStorageTest {
    public static final String GROUP = "com.company.foo";
    public static final String VERSION = "3.25.8";
    public static final Map<String, String> ARTIFACT_FILE_MAP = Map.of(
            "foo-bar", "foobar-3.25.8.jar",
            "foo-baz", "foobaz-3.25.8.jar");
    public static final String DOT = ".";

    private static final String POLICY = "default";
    private static final String REBUILT = "rebuilt";
    private static final String HACBS = "hacbs";

    @Inject
    CacheFacade localCache;

    @ConfigProperty(name = "cache-path")
    Path path;

    @InjectMock
    RebuiltArtifacts rebuiltArtifacts;

    @BeforeEach
    public void setup() {
        Mockito.when(rebuiltArtifacts.isPossiblyRebuilt(ArgumentMatchers.any(String.class))).thenReturn(true);
    }

    @Test
    public void testContainerRegistryBasedArtifactStorage() throws Exception {
        Path containerRegistryCacheRoot = path.resolve(HACBS);
        String groupPath = GROUP.replace(DOT, File.separator);
        try {
            for (Map.Entry<String, String> artifactFile : ARTIFACT_FILE_MAP.entrySet()) {
                testFile(groupPath, artifactFile.getKey(), artifactFile.getValue(), containerRegistryCacheRoot);
            }
        } finally {
            Files.walk(containerRegistryCacheRoot)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void testMissingFile() throws Exception {

        var artifactFile = localCache.getArtifactFile(POLICY, GROUP, "does-not-exist",
                VERSION,
                "does-not-exist", false);
        Assertions.assertFalse(artifactFile.isPresent());
    }

    private void testFile(String groupPath, String artifact, String file, Path containerRegistryCacheRoot) throws IOException {

        Path cachedFile = path.resolve(REBUILT).resolve(groupPath).resolve(artifact).resolve(VERSION)
                .resolve(file);

        var artifactFile = localCache.getArtifactFile(POLICY, GROUP, artifact,
                VERSION, file, false);

        if (artifactFile.isPresent()) {

            var repositoryResult = artifactFile.get();

            String calculatedSha1 = HashUtil.sha1(repositoryResult.getData().readAllBytes());
            String expectedSha1 = repositoryResult.getExpectedSha().orElse("");

            Assertions.assertEquals(expectedSha1, calculatedSha1);
            Assertions.assertFalse(Files.exists(cachedFile)); // We do not use LocalCache
            Assertions.assertTrue(Files.exists(containerRegistryCacheRoot));

            // these files should still have been cached
            artifactFile = localCache.getArtifactFile(POLICY, GROUP, artifact, VERSION, file, false);
            repositoryResult = artifactFile.orElseThrow();
            Assertions.assertNotNull(repositoryResult.getData());

        } else {
            Assertions.fail("Could not download [" + file + "]");
        }
    }
}

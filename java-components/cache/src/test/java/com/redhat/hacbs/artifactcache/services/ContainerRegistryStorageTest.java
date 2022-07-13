package com.redhat.hacbs.artifactcache.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.artifactcache.ContainerRegistryTestResourceManager;
import com.redhat.hacbs.artifactcache.test.util.HashUtil;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTestResource(value = ContainerRegistryTestResourceManager.class, restrictToAnnotatedClass = true)
@QuarkusTest
public class ContainerRegistryStorageTest {
    public static final String GROUP = "io.quarkus";
    public static final String VERSION = "2.10.1.Final";
    public static final Map<String, String> ARTIFACT_FILE_MAP = Map.of(
            "quarkus-vertx-http", "quarkus-vertx-http-2.10.1.Final.jar",
            "quarkus-bootstrap-core", "quarkus-bootstrap-core-2.10.1.Final.jar");

    private static final String POLICY = "prefer-rebuilt";
    private static final String DOT = ".";
    private static final String REBUILT = "rebuilt";
    private static final String HACBS = "hacbs";

    @Inject
    LocalCache localCache;

    @ConfigProperty(name = "cache-path")
    Path path;

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

    private void testFile(String groupPath, String artifact, String file, Path containerRegistryCacheRoot) throws IOException {

        Path cachedFile = path.resolve(REBUILT).resolve(groupPath).resolve(artifact).resolve(VERSION)
                .resolve(file);

        Optional<RepositoryClient.RepositoryResult> artifactFile = localCache.getArtifactFile(POLICY, GROUP, artifact,
                VERSION,
                file);

        if (artifactFile.isPresent()) {

            RepositoryClient.RepositoryResult repositoryResult = artifactFile.get();

            String calculatedSha1 = HashUtil.sha1(repositoryResult.data.readAllBytes());
            String expectedSha1 = repositoryResult.expectedSha.orElse("");

            Assertions.assertEquals(expectedSha1, calculatedSha1);
            Assertions.assertFalse(Files.exists(cachedFile)); // We do not use LocalCache
            Assertions.assertTrue(Files.exists(containerRegistryCacheRoot));

            // these files should still have been cached
            artifactFile = localCache.getArtifactFile(POLICY, GROUP, artifact, VERSION, file);
            repositoryResult = artifactFile.orElseThrow();
            Assertions.assertNotNull(repositoryResult.data);

        } else {
            Assertions.fail("Could not download [" + file + "]");
        }
    }
}

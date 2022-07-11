package com.redhat.hacbs.artifactcache.services;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.artifactcache.test.util.HashUtil;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@Disabled
@QuarkusTest
@TestProfile(QuayIoRegistryStorageIT.QuayTestProfile.class)
public class QuayIoRegistryStorageIT {

    private static final Map<String, String> artifactFileMap = Map.of("quarkus-vertx-http",
            "quarkus-vertx-http-2.10.1.Final.jar",
            "quarkus-bootstrap-core", "quarkus-bootstrap-core-2.10.1.Final.jar");

    public static final String VERSION = "2.10.1.Final";
    public static final String GROUP = "io.quarkus";
    public static final String POLICY = "prefer-rebuilt";

    public static final String DEFAULT = "default";
    public static final String ARTIFACT_STORE = "artifact-store";

    @Inject
    LocalCache localCache;

    @ConfigProperty(name = "cache-path")
    Path path;

    @Test
    public void testQuayBasedArtifactStorage() throws Exception {
        String groupPath = GROUP.replace(DOT, File.separator);

        Set<Map.Entry<String, String>> artifactFileEntries = artifactFileMap.entrySet();

        for (Map.Entry<String, String> artifactFileEntry : artifactFileEntries) {

            String artifact = artifactFileEntry.getKey();
            String file = artifactFileEntry.getValue();
            Path cachedFile = path.resolve(REBUILT).resolve(POLICY).resolve(groupPath).resolve(artifact).resolve(VERSION)
                    .resolve(file);

            try {
                Optional<RepositoryClient.RepositoryResult> artifactFile = localCache.getArtifactFile(POLICY, GROUP, artifact,
                        VERSION,
                        file);
                RepositoryClient.RepositoryResult repositoryResult = artifactFile.get();

                String calculatedSha1 = HashUtil.sha1(repositoryResult.data.readAllBytes());
                String expectedSha1 = repositoryResult.expectedSha.orElse("");

                Assertions.assertEquals(expectedSha1, calculatedSha1);
                Assertions.assertTrue(Files.exists(cachedFile));

                // this file should still have been cached
                artifactFile = localCache.getArtifactFile(POLICY, GROUP, artifact, VERSION, file);
                repositoryResult = artifactFile.get();
                Assertions.assertNotNull(repositoryResult.data);
                Assertions.assertTrue(Files.exists(cachedFile));
            } finally {
                Files.delete(cachedFile);
            }
        }
    }

    public static class QuayTestProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "store.rebuilt.registry", "quay.io",
                    "store.rebuilt.type", "oci_registry",
                    "store.rebuilt.owner", "pkruger");
        }
    }

    private static final String DOT = ".";
    private static final String REBUILT = "rebuilt";
}

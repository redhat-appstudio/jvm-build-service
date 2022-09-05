package com.redhat.hacbs.artifactcache.services;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.artifactcache.S3TestResourceManager;
import com.redhat.hacbs.artifactcache.services.client.s3.S3RepositoryClient;
import com.redhat.hacbs.artifactcache.test.util.HashUtil;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@QuarkusTestResource(S3TestResourceManager.class)
@QuarkusTest
public class S3ArtifactStorageTest {

    public static final String ARTIFACT = "hacbstest";
    public static final String GROUP = "hacbstest";
    public static final String FILE = "test.txt";
    public static final String BAD_HASH = "bad-hash.txt";
    public static final String CONTENTS = "test-file";
    public static final String POLICY = "prefer-rebuilt";
    public static final String VERSION = "1.0";
    public static final String DEFAULT = "default";
    public static final String ARTIFACT_STORE = "artifact-store";

    @Inject
    LocalCache localCache;

    @Inject
    S3Client client;

    @ConfigProperty(name = "cache-path")
    Path path;

    @Test
    public void testS3BasedArtifactStorage() throws Exception {
        String fullTarget = DEFAULT + "/" + GROUP + "/" + ARTIFACT + "/" + VERSION + "/" + FILE;
        client.createBucket(CreateBucketRequest.builder().bucket(ARTIFACT_STORE).build());
        client.putObject(PutObjectRequest.builder().bucket(ARTIFACT_STORE).key(fullTarget)
                .metadata(Map.of(S3RepositoryClient.SHA_1, "BAD_HASH")).build(),
                RequestBody.fromBytes(CONTENTS.getBytes(StandardCharsets.UTF_8)));

        Path cachedFile = path.resolve("rebuilt").resolve(POLICY).resolve(GROUP).resolve(ARTIFACT).resolve(VERSION)
                .resolve(FILE);
        try {
            Optional<RepositoryClient.RepositoryResult> result = localCache.getArtifactFile(POLICY, GROUP, ARTIFACT,
                    VERSION,
                    FILE, null);
            RepositoryClient.RepositoryResult repositoryResult = result.get();
            Assertions.assertEquals(CONTENTS, new String(repositoryResult.data.readAllBytes(), StandardCharsets.UTF_8));
            Assertions.assertFalse(Files.exists(cachedFile));

            String hash = HashUtil.sha1(CONTENTS);
            client.putObject(PutObjectRequest.builder().bucket(ARTIFACT_STORE).key(fullTarget)
                    .metadata(Map.of(S3RepositoryClient.SHA_1, hash)).build(),
                    RequestBody.fromBytes(CONTENTS.getBytes(StandardCharsets.UTF_8)));

            result = localCache.getArtifactFile(POLICY, GROUP, ARTIFACT, VERSION,
                    FILE, null);
            repositoryResult = result.get();
            Assertions.assertEquals(CONTENTS, new String(repositoryResult.data.readAllBytes(), StandardCharsets.UTF_8));
            Assertions.assertTrue(Files.exists(cachedFile));

        } finally {
            client.deleteObject(DeleteObjectRequest.builder().bucket("artifact-store").key(fullTarget).build());
        }

        //even after the delete this file should still have been cached
        var result = localCache.getArtifactFile(POLICY, GROUP, ARTIFACT, VERSION,
                FILE, null);
        var repositoryResult = result.get();
        Assertions.assertEquals(CONTENTS, new String(repositoryResult.data.readAllBytes(), StandardCharsets.UTF_8));
        Assertions.assertTrue(Files.exists(cachedFile));
        Files.delete(cachedFile);
    }

}

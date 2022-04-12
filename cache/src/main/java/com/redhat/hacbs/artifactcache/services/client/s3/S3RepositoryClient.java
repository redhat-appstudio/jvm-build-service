package com.redhat.hacbs.artifactcache.services.client.s3;

import java.util.List;
import java.util.Optional;

import com.redhat.hacbs.artifactcache.services.RepositoryClient;

import io.quarkus.logging.Log;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

public class S3RepositoryClient implements RepositoryClient {

    public static final String SHA_1 = "content-sha1";

    private final S3Client client;
    private final List<String> prefixes;
    private final String bucket;

    public S3RepositoryClient(S3Client client, List<String> prefixes, String bucket) {
        this.client = client;
        this.prefixes = prefixes;
        this.bucket = bucket;
    }

    @Override
    public Optional<RepositoryResult> getArtifactFile(String buildPolicy, String group, String artifact, String version,
            String target) {

        String fullTarget = group + "/" + artifact + "/" + version + "/" + target;
        for (var i : prefixes) {
            String s3key = i + "/" + fullTarget;
            try {
                var response = client.getObject(buildGetRequest(s3key));
                return Optional.of(new RepositoryResult(response, response.response().contentLength(),
                        Optional.ofNullable(response.response().metadata().get(SHA_1)), response.response().metadata()));

            } catch (NoSuchKeyException ignore) {
                Log.tracef("Key %s not found", s3key);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<RepositoryResult> getMetadataFile(String buildPolicy, String group, String target) {
        return Optional.empty();
    }

    private GetObjectRequest buildGetRequest(String objectKey) {
        return GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build();
    }
}

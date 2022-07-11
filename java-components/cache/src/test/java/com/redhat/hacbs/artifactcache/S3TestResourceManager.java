package com.redhat.hacbs.artifactcache;

import java.util.Map;

import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class S3TestResourceManager implements QuarkusTestResourceLifecycleManager {

    LocalStackContainer localstack;

    @Override
    public Map<String, String> start() {
        localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:0.11.5"))
                .withReuse(true)
                .withServices(LocalStackContainer.Service.S3);
        localstack.start();

        return Map.of(
                "quarkus.s3.endpoint-override", localstack.getEndpointOverride(LocalStackContainer.Service.S3).toASCIIString(),
                "quarkus.s3.aws.region", "us-east-1",
                "quarkus.s3.aws.credentials.type", "static",
                "quarkus.s3.aws.credentials.static-provider.access-key-id", localstack.getAccessKey(),
                "quarkus.s3.aws.credentials.static-provider.secret-access-key", localstack.getSecretKey());
    }

    @Override
    public void stop() {
        localstack.stop();
    }
}

package com.redhat.hacbs.container.analyser.deploy.s3;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.redhat.hacbs.container.analyser.deploy.Deployer;

import io.quarkus.logging.Log;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3Deployer implements Deployer {

    final S3Client client;
    final String deploymentBucket;
    final String deploymentPrefix;

    public S3Deployer(S3Client client,
            String deploymentBucket,
            String deploymentPrefix) {
        this.client = client;
        this.deploymentBucket = deploymentBucket;
        this.deploymentPrefix = deploymentPrefix;
    }

    @Override
    public void deployArchive(Path tarGzFile) throws Exception {
        try (TarArchiveInputStream in = new TarArchiveInputStream(
                new GzipCompressorInputStream(Files.newInputStream(tarGzFile)))) {
            TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) {
                Log.infof("Received %s", e.getName());
                byte[] fileData = in.readAllBytes();
                String name = e.getName();
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }
                String targetPath = deploymentPrefix + "/" + name;
                try {
                    client.putObject(PutObjectRequest.builder()
                            .bucket(deploymentBucket)
                            .key(targetPath)
                            .build(), RequestBody.fromBytes(fileData));
                    Log.infof("Deployed to: %s", targetPath);

                } catch (NoSuchBucketException ignore) {
                    //we normally create this on startup
                    client.createBucket(CreateBucketRequest.builder().bucket(deploymentBucket).build());
                    Log.infof("Creating bucked %s after startup and retrying", deploymentBucket);
                    client.putObject(PutObjectRequest.builder()
                            .bucket(deploymentBucket)
                            .key(targetPath)
                            .build(), RequestBody.fromBytes(fileData));
                    Log.infof("Deployed to: %s", targetPath);
                }
            }
        }
    }
}

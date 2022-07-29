package com.redhat.hacbs.sidecar.resources.deploy.s3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.sidecar.resources.deploy.Deployer;
import com.redhat.hacbs.sidecar.resources.deploy.DeployerUtil;

import io.quarkus.logging.Log;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ApplicationScoped
@Named("S3Deployer")
public class S3Deployer implements Deployer {

    final S3Client client;
    final String deploymentBucket;
    final String deploymentPrefix;
    final Set<String> doNotDeploy;

    public S3Deployer(S3Client client,
            @ConfigProperty(name = "deployment-bucket") String deploymentBucket,
            @ConfigProperty(name = "deployment-prefix") String deploymentPrefix,
            @ConfigProperty(name = "ignored-artifacts", defaultValue = "") Optional<Set<String>> doNotDeploy) {
        this.client = client;
        this.deploymentBucket = deploymentBucket;
        this.deploymentPrefix = deploymentPrefix;
        this.doNotDeploy = doNotDeploy.orElse(Set.of());
    }

    @Override
    public void deployArchive(Path tarGzFile) throws Exception {
        try (TarArchiveInputStream in = new TarArchiveInputStream(
                new GzipCompressorInputStream(Files.newInputStream(tarGzFile)))) {
            TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) {
                if (!DeployerUtil.shouldIgnore(doNotDeploy, e.getName())) {
                    Log.infof("Received %s", e.getName());
                    byte[] fileData = in.readAllBytes();
                    String name = e.getName();
                    if (name.startsWith("./")) {
                        name = name.substring(2);
                    }
                    String targetPath = deploymentPrefix + "/" + name;
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
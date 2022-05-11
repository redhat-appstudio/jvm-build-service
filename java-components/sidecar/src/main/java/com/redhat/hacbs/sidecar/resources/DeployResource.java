package com.redhat.hacbs.sidecar.resources;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Singleton;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Path("/deploy")
@Blocking
@Singleton
public class DeployResource {
    final S3Client client;

    final String deploymentBucket;
    final String deploymentPrefix;

    public DeployResource(S3Client client,
            @ConfigProperty(name = "deployment-bucket") String deploymentBucket,
            @ConfigProperty(name = "deployment-prefix") String deploymentPrefix) {
        this.client = client;
        this.deploymentBucket = deploymentBucket;
        this.deploymentPrefix = deploymentPrefix;
    }

    @POST
    public void deployArchive(InputStream data) throws Exception {
        Set<String> contaminants = new HashSet<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new GzipCompressorInputStream(data))) {
            TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) {
                Log.infof("Received %s", e.getName());
                byte[] fileData = in.readAllBytes();
                if (e.getName().endsWith(".class")) {
                    var info = ClassFileTracker.readTrackingInformationFromClass(fileData);
                    if (info != null) {
                        contaminants.add(info.gav);
                    }
                }
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
        Log.infof("Contaminants: %s", contaminants);

    }
}

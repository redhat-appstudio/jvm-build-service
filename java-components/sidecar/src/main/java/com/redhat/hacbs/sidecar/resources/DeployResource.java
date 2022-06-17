package com.redhat.hacbs.sidecar.resources;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.NoCloseInputStream;

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

    final boolean allowPartialDeployment;

    final Set<String> contaminates = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Throwable deployFailure;

    public DeployResource(S3Client client,
            @ConfigProperty(name = "deployment-bucket") String deploymentBucket,
            @ConfigProperty(name = "deployment-prefix") String deploymentPrefix,
            @ConfigProperty(name = "allow-partial-deployment", defaultValue = "false") boolean allowPartialDeployment) {
        this.client = client;
        this.deploymentBucket = deploymentBucket;
        this.deploymentPrefix = deploymentPrefix;
        this.allowPartialDeployment = allowPartialDeployment;
    }

    @GET
    @Path("/result")
    public Response contaminates() {
        if (deployFailure != null) {
            throw new RuntimeException(deployFailure);
        }
        if (contaminates.isEmpty()) {
            return Response.noContent().build();
        } else {
            return Response.ok(String.join(",", contaminates)).build();
        }
    }

    @POST
    public void deployArchive(InputStream data) throws Exception {
        try {
            java.nio.file.Path temp = Files.createTempFile("deployment", ".tar.gz");
            try (var out = Files.newOutputStream(temp)) {
                byte[] buf = new byte[1024];
                int r;
                while ((r = data.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            }

            Set<String> contaminants = new HashSet<>();
            Map<String, Set<String>> contaminatedPaths = new HashMap<>();
            try (TarArchiveInputStream in = new TarArchiveInputStream(
                    new GzipCompressorInputStream(Files.newInputStream(temp)))) {
                TarArchiveEntry e;
                while ((e = in.getNextTarEntry()) != null) {
                    if (e.getName().endsWith(".jar")) {
                        Log.infof("Checking %s for contaminants", e.getName());
                        var info = ClassFileTracker.readTrackingDataFromJar(new NoCloseInputStream(in), e.getName());
                        if (info != null) {
                            List<String> result = info.stream().map(a -> a.gav).toList();
                            contaminants.addAll(result);
                            if (!result.isEmpty()) {
                                int index = e.getName().lastIndexOf("/");
                                if (index != -1) {
                                    contaminatedPaths.computeIfAbsent(e.getName().substring(0, index), s -> new HashSet<>())
                                            .addAll(result);
                                } else {
                                    contaminatedPaths.computeIfAbsent("", s -> new HashSet<>()).addAll(result);
                                }
                            }
                        }
                    }
                }
            }
            Log.infof("Contaminants: %s", contaminatedPaths);
            this.contaminates.addAll(contaminants);

            if (contaminants.isEmpty() || allowPartialDeployment) {
                try (TarArchiveInputStream in = new TarArchiveInputStream(
                        new GzipCompressorInputStream(Files.newInputStream(temp)))) {
                    TarArchiveEntry e;
                    while ((e = in.getNextTarEntry()) != null) {
                        Log.infof("Received %s", e.getName());
                        boolean contaminated = false;
                        for (var i : contaminatedPaths.keySet()) {
                            if (e.getName().startsWith(i)) {
                                contaminated = true;
                                break;
                            }
                        }
                        if (contaminated) {
                            continue;
                        }
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
            } else {
                Log.error("Not performing deployment due to community dependencies contaminating the result");
            }
        } catch (Throwable t) {
            Log.error("Deployment failed", t);
            deployFailure = t;
            flushLogs();
            throw t;
        }
    }

    private void flushLogs() {
        System.err.flush();
        System.out.flush();
    }

}

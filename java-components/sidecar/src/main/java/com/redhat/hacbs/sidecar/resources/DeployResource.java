package com.redhat.hacbs.sidecar.resources;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Path("/deploy")
@Blocking
@Singleton
public class DeployResource {

    final S3Client client;

    final String deploymentBucket;
    final String deploymentPrefix;

    final Set<String> contaminates = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Throwable deployFailure;

    public DeployResource(S3Client client,
            @ConfigProperty(name = "deployment-bucket") String deploymentBucket,
            @ConfigProperty(name = "deployment-prefix") String deploymentPrefix) {
        this.client = client;
        this.deploymentBucket = deploymentBucket;
        this.deploymentPrefix = deploymentPrefix;
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
            try (TarArchiveInputStream in = new TarArchiveInputStream(
                    new GzipCompressorInputStream(Files.newInputStream(temp)))) {
                TarArchiveEntry e;
                while ((e = in.getNextTarEntry()) != null) {
                    if (e.getName().endsWith(".jar")) {
                        Log.infof("Checking %s for contaminants", e.getName());
                        var info = ClassFileTracker.readTrackingDataFromJar(new NoCloseInputStream(in), e.getName());
                        if (info != null) {
                            contaminants.addAll(info.stream().map(a -> a.gav).toList());
                        }
                    }
                }
            }
            Log.infof("Contaminants: %s", contaminants);
            this.contaminates.addAll(contaminants);

            if (contaminants.isEmpty()) {
                try (TarArchiveInputStream in = new TarArchiveInputStream(
                        new GzipCompressorInputStream(Files.newInputStream(temp)))) {
                    TarArchiveEntry e;
                    while ((e = in.getNextTarEntry()) != null) {
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

    private static class NoCloseInputStream extends InputStream {

        final InputStream delegate;

        private NoCloseInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return delegate.read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            //ignore
        }
    }
}

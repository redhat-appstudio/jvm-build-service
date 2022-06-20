package com.redhat.hacbs.sidecar.resources;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern ARTIFACT_PATH = Pattern.compile(".*/([^/]+)/([^/]+)/([^/]+)");

    final S3Client client;

    final String deploymentBucket;
    final String deploymentPrefix;
    /**
     * We can ignore some artifacts that are problematic as part of deployment.
     *
     * Generally this is for things like CLI's, that shade in lots of dependencies,
     * but are not generally useful for downstream builds.
     *
     */
    Set<String> doNotDeploy;

    final Set<String> contaminates = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Throwable deployFailure;

    public DeployResource(S3Client client,
            @ConfigProperty(name = "deployment-bucket") String deploymentBucket,
            @ConfigProperty(name = "deployment-prefix") String deploymentPrefix,
            @ConfigProperty(name = "ignored-artifacts", defaultValue = "") Optional<Set<String>> doNotDeploy) {
        this.client = client;
        this.deploymentBucket = deploymentBucket;
        this.deploymentPrefix = deploymentPrefix;
        this.doNotDeploy = doNotDeploy.orElse(Set.of());
        Log.infof("Ignored Artifacts: %s", doNotDeploy);
    }

    @GET
    @Path("/result")
    public Response contaminates() {
        Log.infof("Getting contaminates for build: " + contaminates);
        if (deployFailure != null) {
            throw new RuntimeException(deployFailure);
        }
        if (contaminates.isEmpty()) {
            return Response.noContent().build();
        } else {
            StringBuilder response = new StringBuilder();
            boolean first = true;
            for (var i : contaminates) {
                //it is recommended that tekton results not be larger than 1k
                //if this happens we just return some of the contaminates
                //it does not matter that much, as if all the reported ones are
                //fixed then we will re-discover the rest next build
                if (response.length() > 400) {
                    Log.error("Contaminates truncated");
                    break;
                }
                if (first) {
                    first = false;
                } else {
                    response.append(",");
                }
                response.append(i);
            }
            Log.infof("Contaminates returned: " + response);
            return Response.ok(response).build();
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
                        if (!shouldIgnore(doNotDeploy, e.getName())) {
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
            }
            Log.infof("Contaminants: %s", contaminatedPaths);
            this.contaminates.addAll(contaminants);

            if (contaminants.isEmpty()) {
                try (TarArchiveInputStream in = new TarArchiveInputStream(
                        new GzipCompressorInputStream(Files.newInputStream(temp)))) {
                    TarArchiveEntry e;
                    while ((e = in.getNextTarEntry()) != null) {
                        if (!shouldIgnore(doNotDeploy, e.getName())) {
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

    static boolean shouldIgnore(Set<String> doNotDeploy, String name) {
        Matcher m = ARTIFACT_PATH.matcher(name);
        if (!m.matches()) {
            return false;
        }
        return doNotDeploy.contains(m.group(1));
    }

    private void flushLogs() {
        System.err.flush();
        System.out.flush();
    }

}

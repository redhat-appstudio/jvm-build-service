package com.redhat.hacbs.sidecar.resources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipException;

import javax.annotation.PreDestroy;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import org.apache.http.Header;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.TrackingData;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import software.amazon.awssdk.services.s3.S3Client;

@Path("/maven2")
@Blocking
@Singleton
public class MavenProxyResource {

    public static final String REBUILT = "rebuilt";
    final CloseableHttpClient remoteClient;
    final String buildPolicy;
    final S3Client client;
    final String deploymentBucket;
    final String deploymentPrefix;

    final String cacheUrl;

    final boolean addTrackingDataToArtifacts;

    final Map<String, String> computedChecksums = new ConcurrentHashMap<>();

    final int retries;

    final int backoff;

    public MavenProxyResource(
            @ConfigProperty(name = "build-policy") String buildPolicy, S3Client client,
            @ConfigProperty(name = "deployment-bucket") String deploymentBucket,
            @ConfigProperty(name = "deployment-prefix") String deploymentPrefix,
            @ConfigProperty(name = "add-tracking-data-to-artifacts", defaultValue = "true") boolean addTrackingDataToArtifacts,
            @ConfigProperty(name = "retries", defaultValue = "5") int retries,
            @ConfigProperty(name = "backoff", defaultValue = "2000") int backoff,
            @ConfigProperty(name = "quarkus.rest-client.cache-service.url") String cacheUrl) {
        remoteClient = HttpClientBuilder.create().build();
        this.buildPolicy = buildPolicy;
        this.client = client;
        this.deploymentBucket = deploymentBucket;
        this.deploymentPrefix = deploymentPrefix;
        this.addTrackingDataToArtifacts = addTrackingDataToArtifacts;
        this.retries = retries;
        this.backoff = backoff;
        this.cacheUrl = cacheUrl;
        Log.infof("Constructing resource manager with build policy %s", buildPolicy);
    }

    @PreDestroy
    void close() throws IOException {
        remoteClient.close();
    }

    @GET
    @Path("{group:.*?}/{artifact}/{version}/{target}")
    public InputStream get(@PathParam("group") String group, @PathParam("artifact") String artifact,
            @PathParam("version") String version, @PathParam("target") String target) throws Exception {
        Exception current = null;
        int currentBackoff = 0;
        //if we fail we retry, don't fail the whole build
        //better to wait for a few seconds and try again than stop a build that has been going for a while
        for (int i = 0; i <= retries; ++i) {
            Log.debugf("Retrieving artifact %s/%s/%s/%s", group, artifact, version, target);
            if (target.endsWith(".sha1")) {
                String key = group + "/" + artifact + "/" + version + "/" + target;
                var modified = computedChecksums.get(key);
                if (modified != null) {
                    return new ByteArrayInputStream(modified.getBytes(StandardCharsets.UTF_8));
                }
            }
            HttpGet httpGet = new HttpGet(cacheUrl + "/maven2/" + group + "/" + artifact + "/" + version + "/" + target);
            httpGet.addHeader("X-build-policy", buildPolicy);

            var response = remoteClient.execute(httpGet);
            try {
                if (response.getStatusLine().getStatusCode() == 404) {
                    throw new NotFoundException();
                } else if (response.getStatusLine().getStatusCode() >= 400) {
                    Log.errorf("Failed to load %s, response code was %s", target, response.getStatusLine().getStatusCode());
                    current = new RuntimeException("Failed to get artifact");
                } else {
                    Header header = response.getFirstHeader("X-maven-repo");
                    String mavenRepoSource;
                    if (header == null) {
                        mavenRepoSource = REBUILT;
                    } else {
                        mavenRepoSource = header.getValue();
                    }
                    if (addTrackingDataToArtifacts && target.endsWith(".jar")) {
                        var tempInput = Files.createTempFile("temp-jar", ".jar");
                        var tempBytecodeTrackedJar = Files.createTempFile("temp-modified-jar", ".jar");
                        try (OutputStream out = Files.newOutputStream(tempInput); var in = response.getEntity().getContent()) {
                            byte[] buf = new byte[1024];
                            int r;
                            while ((r = in.read(buf)) > 0) {
                                out.write(buf, 0, r);
                            }
                            out.close();
                            try (var entityOnDisk = Files.newInputStream(tempInput);
                                    var output = Files.newOutputStream(tempBytecodeTrackedJar)) {
                                HashingOutputStream hashingOutputStream = new HashingOutputStream(output);
                                ClassFileTracker.addTrackingDataToJar(entityOnDisk,
                                        new TrackingData(group.replace("/", ".") + ":" + artifact + ":" + version,
                                                mavenRepoSource),
                                        hashingOutputStream);
                                String key = group + "/" + artifact + "/" + version + "/" + target + ".sha1";
                                hashingOutputStream.close();
                                computedChecksums.put(key, hashingOutputStream.hash);
                                return Files.newInputStream(tempBytecodeTrackedJar);
                            } catch (ZipException e) {
                                return Files.newInputStream(tempInput);
                            }
                        }
                    } else {
                        return response.getEntity().getContent();
                    }
                }
            } catch (Exception e) {
                Log.errorf(e, "Failed to load %s", target);
                current = e;
            }
            currentBackoff += backoff;
            if (i != retries) {
                Log.warnf("Failed retrieving artifact %s/%s/%s/%s, waiting %s seconds", group, artifact, version, target,
                        currentBackoff);
                Thread.sleep(currentBackoff);
            }
        }
        throw current;
    }

    @GET
    @Path("{group:.*?}/maven-metadata.xml{hash:.*?}")
    public InputStream get(@PathParam("group") String group, @PathParam("hash") String hash) throws Exception {
        Log.debugf("Retrieving file %s/maven-metadata.xml%s", group, hash);

        HttpGet httpGet = new HttpGet(cacheUrl + "/maven2/" + group + "/maven-metadata.xml" + hash);
        httpGet.addHeader("X-build-policy", buildPolicy);
        var response = remoteClient.execute(httpGet);
        if (response.getStatusLine().getStatusCode() == 404) {
            throw new NotFoundException();
        } else if (response.getStatusLine().getStatusCode() >= 400) {
            Log.errorf("Failed to load %s, response code was %s", httpGet.getURI(),
                    response.getStatusLine().getStatusCode());
            throw new RuntimeException("Failed to get artifact");
        } else {
            return response.getEntity().getContent();
        }

    }

}

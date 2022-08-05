package com.redhat.hacbs.sidecar.resources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
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
import com.redhat.hacbs.sidecar.resources.relocation.Gav;
import com.redhat.hacbs.sidecar.resources.relocation.RelocationCreator;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;

@Path("/maven2")
@Blocking
@Singleton
public class MavenProxyResource {

    @Inject
    RelocationCreator relocationCreator;

    final CloseableHttpClient remoteClient;
    final String buildPolicy;

    final String cacheUrl;

    final boolean addTrackingDataToArtifacts;

    final Map<String, String> computedChecksums = new ConcurrentHashMap<>();

    final int retries;

    final int backoff;

    final Map<Pattern, String> gavRelocations;

    public MavenProxyResource(
            @ConfigProperty(name = "build-policy") String buildPolicy,
            @ConfigProperty(name = "add-tracking-data-to-artifacts", defaultValue = "true") boolean addTrackingDataToArtifacts,
            @ConfigProperty(name = "retries", defaultValue = "5") int retries,
            @ConfigProperty(name = "backoff", defaultValue = "2000") int backoff,
            @ConfigProperty(name = "quarkus.rest-client.cache-service.url") String cacheUrl,
            GavRelocationConfig gavRelocationConfig) {

        remoteClient = HttpClientBuilder.create().build();
        this.buildPolicy = buildPolicy;
        this.addTrackingDataToArtifacts = addTrackingDataToArtifacts;
        this.retries = retries;
        this.backoff = backoff;
        this.cacheUrl = cacheUrl;

        // Get the relocation patterns
        Map<Pattern, String> m = new HashMap<>();
        for (Map.Entry<String, String> e : gavRelocationConfig.pattern().entrySet()) {
            m.put(Pattern.compile(e.getKey()), e.getValue());
        }
        this.gavRelocations = m;

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

        if (isRelocation(group, artifact, version)) {
            if (target.endsWith(DOT_POM)) {
                return getGavRelocation(group, artifact, version);
            } else if (target.endsWith(DOT_POM_DOT_SHA1)) {
                return getGavRelocationSha1(group, artifact, version);
            } else {
                throw new NotFoundException();
            }
        } else {
            return getGavInputStream(group, artifact, version, target);
        }
    }

    @GET
    @Path("{group:.*?}/maven-metadata.xml{hash:.*?}")
    public InputStream get(@PathParam("group") String group, @PathParam("hash") String hash) throws Exception {
        Log.debugf("Retrieving file %s/maven-metadata.xml%s", group, hash);

        HttpGet httpGet = new HttpGet(cacheUrl + SLASH + MAVEN2 + SLASH + group + SLASH + MAVEN_METADATA_XML + hash);
        httpGet.addHeader(X_BUILD_POLICY, buildPolicy);
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

    private InputStream getGavInputStream(String group, String artifact, String version, String target) throws Exception {
        Exception current = null;
        int currentBackoff = 0;
        //if we fail we retry, don't fail the whole build
        //better to wait for a few seconds and try again than stop a build that has been going for a while
        for (int i = 0; i <= retries; ++i) {
            Log.debugf("Retrieving artifact %s/%s/%s/%s", group, artifact, version, target);
            if (target.endsWith(DOT_SHA1)) {
                String key = group + SLASH + artifact + SLASH + version + SLASH + target;
                var modified = computedChecksums.get(key);
                if (modified != null) {
                    return new ByteArrayInputStream(modified.getBytes(StandardCharsets.UTF_8));
                }
            }
            HttpGet httpGet = new HttpGet(
                    cacheUrl + SLASH + MAVEN2 + SLASH + group + SLASH + artifact + SLASH + version + SLASH + target);
            httpGet.addHeader(X_BUILD_POLICY, buildPolicy);

            var response = remoteClient.execute(httpGet);
            try {
                if (response.getStatusLine().getStatusCode() == 404) {
                    throw new NotFoundException();
                } else if (response.getStatusLine().getStatusCode() >= 400) {
                    Log.errorf("Failed to load %s, response code was %s", target, response.getStatusLine().getStatusCode());
                    current = new RuntimeException("Failed to get artifact");
                } else {
                    if (response == null)
                        throw new NotFoundException();
                    Header header = response.getFirstHeader(X_MAVEN_REPO);
                    String mavenRepoSource;
                    if (header == null) {
                        mavenRepoSource = REBUILT;
                    } else {
                        mavenRepoSource = header.getValue();
                    }
                    if (addTrackingDataToArtifacts && target.endsWith(DOT_JAR)) {
                        var tempInput = Files.createTempFile(TEMP_JAR, DOT_JAR);
                        var tempBytecodeTrackedJar = Files.createTempFile(TEMP_MODIFIED_JAR, DOT_JAR);
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
                                        new TrackingData(toGroupId(group) + DOUBLE_POINT + artifact + DOUBLE_POINT + version,
                                                mavenRepoSource),
                                        hashingOutputStream);
                                String key = group + SLASH + artifact + SLASH + version + SLASH + target + DOT_SHA1;
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

    private InputStream getGavRelocation(String group, String artifact, String version) {
        byte[] b = getGavRelocationBytes(group, artifact, version);
        return new ByteArrayInputStream(b);
    }

    private InputStream getGavRelocationSha1(String group, String artifact, String version) {
        byte[] b = getGavRelocationBytes(group, artifact, version);
        String sha1 = HashUtil.sha1(b);
        return new ByteArrayInputStream(sha1.getBytes());
    }

    private byte[] getGavRelocationBytes(String group, String artifact, String version) {
        group = toGroupId(group);
        String fromKey = toGavKey(group, artifact, version);
        Map.Entry<Pattern, String> match = findFirstMatch(fromKey).get();

        Pattern pattern = match.getKey();
        Matcher matcher = pattern.matcher(fromKey);
        String toKey = match.getValue();

        if (matcher.matches()) {
            for (int i = 1; i <= matcher.groupCount(); ++i) {
                toKey = toKey.replaceAll(DOLLAR_VAR + i, matcher.group(i));
            }
            return relocationCreator.create(new Gav(group, artifact, version), toGav(toKey));
        }
        throw new NotFoundException();
    }

    private boolean isRelocation(String group, String artifact, String version) {
        if (!gavRelocations.isEmpty()) {
            group = toGroupId(group);
            String key = toGavKey(group, artifact, version);

            Optional<Map.Entry<Pattern, String>> match = findFirstMatch(key);

            return match.isPresent();
        }
        return false;
    }

    private Optional<Map.Entry<Pattern, String>> findFirstMatch(String key) {
        return gavRelocations.entrySet()
                .stream()
                .filter(s -> s.getKey().matcher(key).matches())
                .findFirst();
    }

    private String toGroupId(String group) {
        return group.replaceAll(SLASH, DOT);
    }

    private String toGavKey(String group, String artifact, String version) {
        return String.format(GAV_PATTERN, group, artifact, version);
    }

    private Gav toGav(String key) {
        if (key.contains(DOUBLE_POINT)) {
            String[] parts = key.split(DOUBLE_POINT);
            if (parts.length == 3) {
                return new Gav(parts[0], parts[1], parts[2]);
            }
        }
        throw new RuntimeException("Invalid mapping for [" + key + "].");
    }

    private static final String GAV_PATTERN = "%s:%s:%s";
    private static final String DOUBLE_POINT = ":";
    private static final String DOLLAR_VAR = "\\$";
    private static final String SLASH = "/";
    private static final String DOT = ".";
    private static final String DOT_SHA1 = ".sha1";
    private static final String DOT_JAR = ".jar";
    private static final String DOT_POM = ".pom";
    private static final String DOT_POM_DOT_SHA1 = DOT_POM + DOT_SHA1;
    private static final String X_MAVEN_REPO = "X-maven-repo";
    private static final String X_BUILD_POLICY = "X-build-policy";
    private static final String TEMP_JAR = "temp-jar";
    private static final String TEMP_MODIFIED_JAR = "temp-modified-jar";
    private static final String REBUILT = "rebuilt";
    private static final String MAVEN2 = "maven2";
    private static final String MAVEN_METADATA_XML = "maven-metadata.xml";
}

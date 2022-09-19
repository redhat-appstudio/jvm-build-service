package com.redhat.hacbs.artifactcache.services.client.maven;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.microprofile.config.ConfigProvider;

import com.redhat.hacbs.artifactcache.services.ArtifactResult;
import com.redhat.hacbs.artifactcache.services.RepositoryClient;

import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;

public class MavenClient implements RepositoryClient {

    public static final String SHA_1 = ".sha1";
    private final String name;
    private final URI uri;

    private final String stringUri;
    final CloseableHttpClient remoteClient;
    final CurrentVertxRequest currentVertxRequest;

    public MavenClient(String name, URI uri) {
        int threads = ConfigProvider.getConfig().getOptionalValue("quarkus.thread.pool.max.threads", Integer.class).orElse(10);
        remoteClient = HttpClientBuilder.create().disableAutomaticRetries().setMaxConnPerRoute(threads).setMaxConnTotal(threads)
                .build();
        this.name = name;
        this.uri = uri;
        this.stringUri = uri.toASCIIString();
        currentVertxRequest = Arc.container().instance(CurrentVertxRequest.class).get();
    }

    public static MavenClient of(String name, URI uri) {
        return new MavenClient(name, uri);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Optional<ArtifactResult> getArtifactFile(String group, String artifact, String version,
            String target) {
        Log.debugf("Retrieving artifact %s/%s/%s/%s from repo %s at %s", group, artifact, version, target, name, uri);
        String targetUri = uri + "/" + group + "/" + artifact + "/" + version + "/" + target;
        return downloadMavenFile(group, artifact, version, target, targetUri);

    }

    private Optional<ArtifactResult> downloadMavenFile(String group, String artifact, String version, String target,
            String targetUri) {

        CloseableHttpResponse response = null;
        try {
            String sha1 = null;
            //this must be first, having it lower down can create a deadlock in some situations
            //if all the connections are being used to download the main artifacts then there are
            //none in the pool to download the shas
            if (!target.endsWith(SHA_1)) {
                try (var hash = remoteClient.execute(new HttpGet(
                        targetUri + SHA_1))) {
                    if (hash.getStatusLine().getStatusCode() == 404) {
                        Log.debugf("Could not find sha1 hash for artifact %s/%s/%s/%s from repo %s at %s", group, artifact,
                                version,
                                target, name, uri);
                    } else {
                        sha1 = new String(hash.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8).trim();
                        //older maven version would deploy sha files with extra stuff after the sha
                        if (sha1.contains(" ")) {
                            sha1 = sha1.split(" ")[0];
                        }
                    }
                }
            }
            HttpGet httpGet = new HttpGet(targetUri);
            try {
                response = remoteClient.execute(httpGet);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (response.getStatusLine().getStatusCode() == 404) {
                response.close();
                return Optional.empty();
            }
            if (response.getStatusLine().getStatusCode() != 200) {
                response.close();
                throw new RuntimeException("Unexpected status code: " + response.getStatusLine().getStatusCode());
            }
            Map<String, String> headers = new HashMap<>();
            for (var i : response.getAllHeaders()) {
                headers.put(i.getName(), i.getValue());
            }
            Log.debugf("Found artifact %s/%s/%s/%s from repo %s at %s", group, artifact, version, target, name, uri);
            return Optional.of(new ArtifactResult(new CloseDelegateInputStream(response.getEntity().getContent(), response),
                    response.getEntity().getContentLength(),
                    Optional.ofNullable(sha1), headers));
        } catch (Exception e) {
            try {
                if (response != null) {
                    response.close();
                }
            } catch (IOException ex) {
                //ignore
            }
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<ArtifactResult> getMetadataFile(String group, String target) {
        Log.debugf("Retrieving metadata %s/%s from repo %s at %s", group, target, name, uri);
        return downloadMavenFile(group, null, null, target, uri + "/" + group + "/" + target);

    }

    @Override
    public String toString() {
        return "MavenClient{" +
                "name='" + name + '\'' +
                ", uri=" + uri +
                '}';
    }
}

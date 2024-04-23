package com.redhat.hacbs.artifactcache.services.client.maven;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.eclipse.microprofile.config.ConfigProvider;

import com.redhat.hacbs.artifactcache.services.ArtifactResult;
import com.redhat.hacbs.artifactcache.services.RepositoryClient;
import com.redhat.hacbs.artifactcache.util.RequestCleanup;

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

    final RequestCleanup requestCleanup;

    final int networkRetries;

    public MavenClient(String name, URI uri, int networkRetries, String username, String password) {
        this.networkRetries = networkRetries;
        int threads = ConfigProvider.getConfig().getOptionalValue("quarkus.thread.pool.max.threads", Integer.class).orElse(10);
        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create().disableAutomaticRetries().setMaxConnPerRoute(threads)
                .setMaxConnTotal(threads);
        if (username != null && password != null) {
            String header = "Basic "
                    + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            httpClientBuilder.addInterceptorLast(new HttpRequestInterceptor() {
                @Override
                public void process(HttpRequest httpRequest, HttpContext httpContext) throws HttpException, IOException {
                    httpRequest.addHeader("Authorization", header);
                }
            });
        }
        remoteClient = httpClientBuilder
                .build();
        this.name = name;
        this.uri = uri;
        this.stringUri = uri.toASCIIString();
        currentVertxRequest = Arc.container().instance(CurrentVertxRequest.class).get();
        requestCleanup = RequestCleanup.instance();
    }

    public static MavenClient of(String name, URI uri) {
        //we hard code a single retry at this point
        return new MavenClient(name, uri, 1, null, null);
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
        long backoff = 10000;
        IOException networkException = null;
        int retryCount = 0;
        String sha1 = null;
        while (retryCount <= networkRetries) {

            CloseableHttpResponse response = null;
            try {
                //this must be first, having it lower down can create a deadlock in some situations
                //if all the connections are being used to download the main artifacts then there are
                //none in the pool to download the shas
                try {
                    if (!target.endsWith(SHA_1) && sha1 == null) {
                        try (var hash = remoteClient.execute(new HttpGet(
                                targetUri + SHA_1))) {
                            if (hash.getStatusLine().getStatusCode() == 404) {
                                hash.getEntity().getContent().readAllBytes(); //read the 404 page
                                Log.debugf("Could not find sha1 hash for artifact %s/%s/%s/%s from repo %s at %s", group,
                                        artifact,
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
                    response = remoteClient.execute(httpGet);
                    requestCleanup.addResource(response);
                } catch (IOException e) {
                    //if we have a network issues we might want to retry
                    networkException = e;
                    retryCount++;
                    if (retryCount <= networkRetries) {
                        Log.infof("Retrying request to %s due to network failure %s", targetUri, e.getMessage());
                        Thread.sleep(backoff);
                        backoff += backoff;
                    }
                    continue;
                }
                if (response.getStatusLine().getStatusCode() == 404) {
                    Log.infof("404 downloading artifact %s", targetUri);
                    closeResponse(response);
                    return Optional.empty();
                }
                if (response.getStatusLine().getStatusCode() != 200) {
                    closeResponse(response);
                    Log.errorf(
                            "Unexpected status code: " + response.getStatusLine().getStatusCode() + " downloading %s from %s",
                            target, targetUri);
                    return Optional.empty();
                }
                Map<String, String> headers = new HashMap<>();
                for (var i : response.getAllHeaders()) {
                    headers.put(i.getName(), i.getValue());
                }
                Log.debugf("Found artifact %s/%s/%s/%s from repo %s at %s", group, artifact, version, target, name, uri);
                return Optional
                        .of(new ArtifactResult(null, new CloseDelegateInputStream(response.getEntity().getContent(), response),
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
        throw new RuntimeException(networkException);
    }

    private void closeResponse(CloseableHttpResponse response) throws IOException {
        byte[] buff = new byte[1024];
        while (response.getEntity().getContent().read(buff) > 0) {

        }
        response.close();
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

package com.redhat.hacbs.artifactcache.services.mavenclient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;

import javax.ws.rs.NotFoundException;

import org.eclipse.microprofile.rest.client.RestClientBuilder;

import com.redhat.hacbs.artifactcache.services.RepositoryClient;

import io.quarkus.logging.Log;

public class MavenClient implements RepositoryClient {

    public static final String SHA_1 = ".sha1";
    private final String name;
    private final URI uri;
    private final MavenHttpClient client;

    public MavenClient(String name, URI uri, MavenHttpClient client) {
        this.name = name;
        this.uri = uri;
        this.client = client;
    }

    public static MavenClient of(String name, URI uri) {
        MavenHttpClient client = RestClientBuilder.newBuilder().baseUri(uri).build(MavenHttpClient.class);
        return new MavenClient(name, uri, client);
    }

    @Override
    public Optional<RepositoryResult> getArtifactFile(String group, String artifact, String version, String target) {
        Log.debugf("Retrieving artifact %s/%s/%s/%s from repo %s at %s", group, artifact, version, target, name, uri);
        try {
            var data = client.getArtifactFile(group, artifact, version, target);
            String sha1 = null;
            if (!target.endsWith(SHA_1)) {
                try {
                    var hash = client.getArtifactFile(group, artifact, version, target + SHA_1);
                    sha1 = new String(hash.readAllBytes(), StandardCharsets.UTF_8);
                } catch (NotFoundException | IOException e) {
                    Log.debugf("Could not find sha1 hash for artifact %s/%s/%s/%s from repo %s at %s", group, artifact, version,
                            target, name, uri);
                }
            }
            Log.debugf("Found artifact %s/%s/%s/%s from repo %s at %s", group, artifact, version, target, name, uri);
            return Optional.of(new RepositoryResult(data, Optional.ofNullable(sha1), Collections.emptyMap()));
        } catch (NotFoundException e) {
            Log.debugf("Could not find artifact %s/%s/%s/%s from repo %s at %s", group, artifact, version, target, name, uri);
            return Optional.empty();
        }

    }

    @Override
    public Optional<RepositoryResult> getMetadataFile(String group, String target) {
        Log.debugf("Retrieving metadata %s/%s from repo %s at %s", group, target, name, uri);
        try {
            var data = client.getMetadataFile(group, target);
            String sha1 = null;
            if (!target.endsWith(SHA_1)) {
                try {
                    var hash = client.getMetadataFile(group, target + SHA_1);
                    sha1 = new String(hash.readAllBytes(), StandardCharsets.UTF_8);
                } catch (NotFoundException | IOException e) {
                    Log.debugf("Could not find sha1 hash for metadata %s/%s from repo %s at %s", group, target, name, uri);
                }
            }
            Log.debugf("Found metadata %s/%s from repo %s at %s", group, target, name, uri);
            return Optional.of(new RepositoryResult(data, Optional.ofNullable(sha1), Collections.emptyMap()));
        } catch (NotFoundException e) {
            Log.debugf("Could not find metadata %s/%s from repo %s at %s", group, target, name, uri);
            return Optional.empty();
        }
    }
}

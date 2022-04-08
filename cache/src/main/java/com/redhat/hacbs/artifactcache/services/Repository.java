package com.redhat.hacbs.artifactcache.services;

import java.net.URI;

public class Repository {

    final String name;
    final URI uri;
    final RepositoryType type;
    final RepositoryClient client;

    public Repository(String name, URI uri, RepositoryType type, RepositoryClient client) {
        this.name = name;
        this.uri = uri;
        this.type = type;
        this.client = client;
    }

    public String getName() {
        return name;
    }

    public URI getUri() {
        return uri;
    }

    public RepositoryType getType() {
        return type;
    }

    public RepositoryClient getClient() {
        return client;
    }
}

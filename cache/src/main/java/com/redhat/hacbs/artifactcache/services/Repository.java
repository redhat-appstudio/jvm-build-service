package com.redhat.hacbs.artifactcache.services;

import java.net.URI;

/**
 * A runtime representation of a repository or artifact store
 */
public class Repository {

    private final String name;
    private final URI uri;
    private final RepositoryType type;
    private final RepositoryClient client;

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

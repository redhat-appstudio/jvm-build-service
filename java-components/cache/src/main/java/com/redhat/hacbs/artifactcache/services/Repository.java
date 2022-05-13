package com.redhat.hacbs.artifactcache.services;

/**
 * A runtime representation of a repository or artifact store
 */
public class Repository {

    private final String name;
    private final String uri;
    private final RepositoryType type;
    private final RepositoryClient client;

    public Repository(String name, String uri, RepositoryType type, RepositoryClient client) {
        this.name = name;
        this.uri = uri;
        this.type = type;
        this.client = client;
    }

    public String getName() {
        return name;
    }

    public String getUri() {
        return uri;
    }

    public RepositoryType getType() {
        return type;
    }

    public RepositoryClient getClient() {
        return client;
    }
}

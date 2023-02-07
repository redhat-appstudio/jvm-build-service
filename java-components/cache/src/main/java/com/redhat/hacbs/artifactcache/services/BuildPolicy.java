package com.redhat.hacbs.artifactcache.services;

import java.util.Collections;
import java.util.List;

public class BuildPolicy {

    final List<RepositoryCache> repositories;

    public BuildPolicy(List<RepositoryCache> repositories) {
        this.repositories = Collections.unmodifiableList(repositories);
    }

    public List<RepositoryCache> getRepositories() {
        return repositories;
    }
}

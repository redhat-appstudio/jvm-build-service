package com.redhat.hacbs.artifactcache.services;

import java.util.Collections;
import java.util.List;

public class BuildPolicy {

    final List<Repository> repositories;

    public BuildPolicy(List<Repository> repositories) {
        this.repositories = Collections.unmodifiableList(repositories);
    }

    public List<Repository> getRepositories() {
        return repositories;
    }
}

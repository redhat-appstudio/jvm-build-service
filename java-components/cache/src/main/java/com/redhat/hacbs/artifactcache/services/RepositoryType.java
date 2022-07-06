package com.redhat.hacbs.artifactcache.services;

public enum RepositoryType {

    MAVEN2(false),
    S3(true),
    DOCKER_REGISTRY(true);

    final boolean buildPolicyUsed;

    RepositoryType(boolean buildPolicyUsed) {
        this.buildPolicyUsed = buildPolicyUsed;
    }

    public boolean isBuildPolicyUsed() {
        return buildPolicyUsed;
    }
}

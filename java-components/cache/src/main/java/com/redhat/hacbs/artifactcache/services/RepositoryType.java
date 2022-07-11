package com.redhat.hacbs.artifactcache.services;

public enum RepositoryType {

    MAVEN2(false),
    S3(true),
    OCI_REGISTRY(true);

    final boolean buildPolicyUsed;

    RepositoryType(boolean buildPolicyUsed) {
        this.buildPolicyUsed = buildPolicyUsed;
    }

    public boolean isBuildPolicyUsed() {
        return buildPolicyUsed;
    }
}

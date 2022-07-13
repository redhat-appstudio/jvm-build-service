package com.redhat.hacbs.artifactcache.services;

public enum RepositoryType {

    MAVEN2(false, false),
    S3(true, false),
    OCI_REGISTRY(false, true);

    final boolean buildPolicyUsed;
    final boolean ignoreCache;

    RepositoryType(boolean buildPolicyUsed, boolean ignoreCache) {
        this.buildPolicyUsed = buildPolicyUsed;
        this.ignoreCache = ignoreCache;
    }

    public boolean isBuildPolicyUsed() {
        return buildPolicyUsed;
    }

    public boolean shouldIgnoreLocalCache() {
        return ignoreCache;
    }
}

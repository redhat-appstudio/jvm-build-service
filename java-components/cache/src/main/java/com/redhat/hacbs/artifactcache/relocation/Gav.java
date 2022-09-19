package com.redhat.hacbs.artifactcache.relocation;

public final class Gav {

    private final String groupId;
    private final String artifactId;
    private final String version;

    public Gav(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

}

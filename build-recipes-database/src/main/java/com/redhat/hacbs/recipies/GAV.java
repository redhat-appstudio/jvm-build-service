package com.redhat.hacbs.recipies;

import java.util.Objects;

public class GAV {

    private final String groupId;
    private final String artifactId;
    private final String version;

    public GAV(String groupId, String artifactId, String version) {
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

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        GAV gav = (GAV) o;
        return Objects.equals(groupId, gav.groupId) && Objects.equals(artifactId, gav.artifactId)
                && Objects.equals(version, gav.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version);
    }
}

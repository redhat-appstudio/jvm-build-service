package com.redhat.hacbs.recipes;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An artifact GAV
 */
public class GAV {

    final String groupId;
    final String artifactId;
    final String version;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public GAV(@JsonProperty("groupId") String groupId, @JsonProperty("artifactId") String artifactId,
            @JsonProperty("version") String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    public static GAV parse(String gav) {
        var split = gav.split(":");
        if (split.length != 3) {
            throw new IllegalArgumentException("not a GAV: " + gav);
        }
        return new GAV(split[0], split[1], split[2]);
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
        GAV that = (GAV) o;
        return Objects.equals(groupId, that.groupId) && Objects.equals(artifactId, that.artifactId)
                && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version);
    }

    @Override
    public String toString() {
        return "GAV{" +
                "groupId='" + groupId + '\'' +
                ", artifactId='" + artifactId + '\'' +
                ", version='" + version + '\'' +
                '}';
    }
}

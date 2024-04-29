package com.redhat.hacbs.container.deploy;

import java.util.Objects;

import org.apache.commons.codec.digest.DigestUtils;

public final class Gav {

    private static final String GAV_FORMAT = "%s:%s:%s";

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String tag;

    private Gav(String groupId, String artifactId, String version, String tag) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.tag = tag;
    }

    public static Gav parse(String gav) {
        var parts = gav.split(":");
        return create(parts[0], parts[1], parts[2]);
    }

    public static Gav create(String groupId, String artifactId, String version) {
        String tag = DigestUtils.sha256Hex(String.format(GAV_FORMAT, groupId, artifactId, version));
        return new Gav(groupId, artifactId, version, tag);
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

    public String getTag() {
        return tag;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 23 * hash + Objects.hashCode(this.groupId);
        hash = 23 * hash + Objects.hashCode(this.artifactId);
        hash = 23 * hash + Objects.hashCode(this.version);
        hash = 23 * hash + Objects.hashCode(this.tag);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Gav other = (Gav) obj;
        if (!Objects.equals(this.groupId, other.groupId)) {
            return false;
        }
        if (!Objects.equals(this.artifactId, other.artifactId)) {
            return false;
        }
        if (!Objects.equals(this.version, other.version)) {
            return false;
        }
        return Objects.equals(this.tag, other.tag);
    }

    public String stringForm() {
        return groupId + ":" + artifactId + ":" + version;
    }

    @Override
    public String toString() {
        return "GAV{" + "groupId=" + groupId + ", artifactId=" + artifactId + ", version=" + version + ", tag=" + tag + '}';
    }
}

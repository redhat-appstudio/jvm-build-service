package com.redhat.hacbs.sidecar.resources.deploy.containerregistry;

import java.util.Objects;

public class Gav {
    private String groupId;
    private String artifactId;
    private String version;
    private String tag;

    public Gav() {
    }

    public Gav(String groupId, String artifactId, String version, String tag) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.tag = tag;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
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

    @Override
    public String toString() {
        return "GAV{" + "groupId=" + groupId + ", artifactId=" + artifactId + ", version=" + version + ", tag=" + tag + '}';
    }
}

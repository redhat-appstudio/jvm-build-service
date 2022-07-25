package com.redhat.hacbs.sidecar.resources.deploy.containerregistry;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ImageData {

    private final Path artifactsPath;
    private final Set<Gav> gavs;

    public ImageData(Path artifactsPath, Set<Gav> gavs) {
        this.artifactsPath = artifactsPath;
        this.gavs = gavs;
    }

    public Path getArtifactsPath() {
        return artifactsPath;
    }

    public Set<Gav> getGavs() {
        return gavs;
    }

    public String getVersions() {
        if (this.gavs == null || this.gavs.isEmpty())
            return null;
        Set<String> versionList = this.gavs.stream().map(Gav::getVersion)
                .collect(Collectors.toSet());
        return String.join(COMMA, versionList);
    }

    public String getGroupIds() {
        if (this.gavs == null || this.gavs.isEmpty())
            return null;
        Set<String> groupIdList = this.gavs.stream().map(Gav::getGroupId)
                .collect(Collectors.toSet());
        return String.join(COMMA, groupIdList);
    }

    public String getArtifactIds() {
        if (this.gavs == null || this.gavs.isEmpty())
            return null;
        Set<String> artifactIdList = this.gavs.stream().map(Gav::getArtifactId)
                .collect(Collectors.toSet());
        return String.join(COMMA, artifactIdList);
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 73 * hash + Objects.hashCode(this.artifactsPath);
        hash = 73 * hash + Objects.hashCode(this.gavs);
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
        final ImageData other = (ImageData) obj;
        if (!Objects.equals(this.artifactsPath, other.artifactsPath)) {
            return false;
        }
        return Objects.equals(this.gavs, other.gavs);
    }

    @Override
    public String toString() {
        return "ImageData{" + "artifactsPath=" + artifactsPath + ", gavs=" + gavs + '}';
    }

    private static final String COMMA = ", ";
}

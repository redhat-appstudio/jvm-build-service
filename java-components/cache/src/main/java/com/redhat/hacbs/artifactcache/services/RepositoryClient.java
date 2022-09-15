package com.redhat.hacbs.artifactcache.services;

import java.util.Optional;

public interface RepositoryClient {

    String getName();

    /**
     * Retrieves an artifact related file.
     *
     * @param group The group
     * @param artifact The artifact
     * @param version The version
     * @param target The target file
     * @return empty if the file is not present, otherwise the file data
     */
    Optional<ArtifactResult> getArtifactFile(String group, String artifact, String version, String target);

    /**
     * Retrieves a metadata file from the repository
     *
     * @param group The group
     * @param target The target file
     * @return empty if the file is not present, otherwise the file data
     */
    Optional<ArtifactResult> getMetadataFile(String group, String target);

}

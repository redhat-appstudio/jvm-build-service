package com.redhat.hacbs.artifactcache.services;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public interface RepositoryClient {

    /**
     * Retrieves an artifact related file.
     *
     *
     * @param group The group
     * @param artifact The artifact
     * @param version The version
     * @param target The target file
     * @return empty if the file is not present, otherwise the file data
     */
    Optional<RepositoryResult> getArtifactFile(String buildPolicy, String group, String artifact, String version,
            String target, Long buildStartTime);

    /**
     * Retrieves a metadata file from the repository
     *
     * @param group The group
     * @param target The target file
     * @return empty if the file is not present, otherwise the file data
     */
    Optional<RepositoryResult> getMetadataFile(String buildPolicy, String group, String target);

    class RepositoryResult {

        final InputStream data;
        final long size;
        final Optional<String> expectedSha;
        final Map<String, String> metadata;

        public RepositoryResult(InputStream data, long size, Optional<String> expectedSha, Map<String, String> metadata) {
            this.data = data;
            this.size = size;
            this.expectedSha = expectedSha;
            this.metadata = metadata;
        }

        public InputStream getData() {
            return data;
        }

        public Optional<String> getExpectedSha() {
            return expectedSha;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public long getSize() {
            return size;
        }
    }
}

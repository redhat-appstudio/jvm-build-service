package com.redhat.hacbs.artifactcache.services;

import java.io.IOException;
import java.nio.file.Path;

public interface StorageManager {
    /**
     * Get access to a directory to store artifacts. This directory is considered a discrete unit, and if disk usage is too
     * high then this whole directory may be deleted at any point.
     *
     * @param relative The directory to access
     * @return An access token to access the directory
     */
    Path accessDirectory(String relative) throws IOException;

    /**
     * Get access to the provided file. The files parent directory is considered a discrete unit, and if disk usage is too
     * high then this whole directory may be deleted at any point.
     *
     *
     *
     * @param relative The directory to access
     * @return An access token to access the directory
     */
    Path accessFile(String relative) throws IOException;

    /**
     * Resolves a new relative storage manager. The underlying manager is still the same, but paths are resolved relative to a
     * different path.
     *
     * @param relative
     * @return
     */
    StorageManager resolve(String relative);

    void delete(String relative);

    String path();

    void clear();

}

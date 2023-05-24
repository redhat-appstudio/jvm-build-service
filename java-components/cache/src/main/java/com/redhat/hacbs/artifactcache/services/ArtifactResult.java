package com.redhat.hacbs.artifactcache.services;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.logging.Log;

public class ArtifactResult implements AutoCloseable {

    private final Path file;
    private final InputStream data;
    private final long size;
    private final Optional<String> expectedSha;
    private final Map<String, String> metadata;
    private final List<Runnable> closeTasks;

    public ArtifactResult(Path file, InputStream data, long size, Optional<String> expectedSha, Map<String, String> metadata,
            Runnable... closeTasks) {
        this.file = file;
        this.data = data;
        this.size = size;
        this.expectedSha = expectedSha;
        this.metadata = metadata;
        this.closeTasks = new ArrayList<>(Arrays.asList(closeTasks));
    }

    public InputStream getData() {
        return data;
    }

    /**
     * Returns a Path if present, otherwise the InputStream that can be used to read the data.
     *
     * As the Path object can be served more efficiently, JAX-RS will get improved performance when the Path is in use.
     *
     */
    public Object getFileOrStream() {
        if (file != null) {
            return file;
        }
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

    @Override
    public void close() throws Exception {
        try {
            data.close();
        } finally {
            for (var i : closeTasks) {
                try {
                    i.run();
                } catch (Throwable t) {
                    Log.error("Failed to run close task", t);
                }
            }
        }
    }
}

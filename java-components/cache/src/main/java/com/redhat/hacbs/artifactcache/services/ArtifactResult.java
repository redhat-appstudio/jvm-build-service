package com.redhat.hacbs.artifactcache.services;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.logging.Log;

public class ArtifactResult implements AutoCloseable {

    private final InputStream data;
    private final long size;
    private final Optional<String> expectedSha;
    private final Map<String, String> metadata;
    private final List<Runnable> closeTasks;

    public ArtifactResult(InputStream data, long size, Optional<String> expectedSha, Map<String, String> metadata,
            Runnable... closeTasks) {
        this.data = data;
        this.size = size;
        this.expectedSha = expectedSha;
        this.metadata = metadata;
        this.closeTasks = new ArrayList<>(Arrays.asList(closeTasks));
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

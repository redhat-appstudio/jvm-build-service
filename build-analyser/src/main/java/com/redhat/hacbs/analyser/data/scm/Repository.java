package com.redhat.hacbs.analyser.data.scm;

import java.util.Comparator;
import java.util.Objects;

public class Repository implements Comparable<Repository> {

    private String uri;
    private String type;
    private boolean processed;
    private boolean failed;
    private String processor;
    private String failedReason;
    /**
     * A unique UUID. This can be used when checking out the repository locally to a unique directory.
     */
    private String uuid;

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isProcessed() {
        return processed;
    }

    public void setProcessed(boolean processed) {
        this.processed = processed;
    }

    public String getProcessor() {
        return processor;
    }

    public void setProcessor(String processor) {
        this.processor = processor;
    }

    public boolean isFailed() {
        return failed;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }

    public String getFailedReason() {
        return failedReason;
    }

    public void setFailedReason(String failedReason) {
        this.failedReason = failedReason;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public int compareTo(Repository o) {
        return Objects.compare(uri, o.uri, Comparator.naturalOrder());
    }
}

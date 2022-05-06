package com.redhat.hacbs.analyser.data.scm;

import java.util.Comparator;
import java.util.Objects;

public class Repository implements Comparable<Repository> {

    private String uri;
    private String type;
    private boolean processed;
    private boolean failed;
    private boolean disabled;
    private String processor;
    private String failedReason;
    /**
     * A unique UUID. This can be used when checking out the repository locally to a unique directory.
     * <p>
     * This is volatile to support multithreaded eager checkout
     */
    private volatile String uuid;

    /**
     * If this is true then the repo should only be used to mark known versions from tags.
     *
     * There is a newer repo elsewhere that should be used for all other versions.
     */
    private boolean deprecated;

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

    public boolean isDeprecated() {
        return deprecated;
    }

    public void setDeprecated(boolean deprecated) {
        this.deprecated = deprecated;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    @Override
    public int compareTo(Repository o) {
        return Objects.compare(uri, o.uri, Comparator.naturalOrder());
    }
}

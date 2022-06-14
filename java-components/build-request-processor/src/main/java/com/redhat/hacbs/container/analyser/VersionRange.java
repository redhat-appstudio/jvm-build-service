package com.redhat.hacbs.container.analyser;

/**
 * Represents a tool required by the build, and the minimum and maximum versions supported.
 *
 * Version may be null, as this cannot always be determined.
 */
public class VersionRange {
    String min;
    String max;

    String preferred;

    public VersionRange(String min, String max, String preferred) {
        this.min = min;
        this.max = max;
        this.preferred = preferred;
    }

    public VersionRange() {
    }

    public String getMin() {
        return min;
    }

    public VersionRange setMin(String min) {
        this.min = min;
        return this;
    }

    public String getMax() {
        return max;
    }

    public VersionRange setMax(String max) {
        this.max = max;
        return this;
    }

    public String getPreferred() {
        return preferred;
    }

    public VersionRange setPreferred(String preferred) {
        this.preferred = preferred;
        return this;
    }
}

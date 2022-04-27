package com.redhat.hacbs.classfile.tracker;

import java.util.Objects;

public class TrackingData {

    public final String gav;
    public final String source;

    public TrackingData(String gav, String source) {
        this.gav = gav;
        this.source = source;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        TrackingData that = (TrackingData) o;
        return Objects.equals(gav, that.gav) && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gav, source);
    }

    @Override
    public String toString() {
        return "TrackingData{" +
                "gav='" + gav + '\'' +
                ", source='" + source + '\'' +
                '}';
    }
}

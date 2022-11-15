package com.redhat.hacbs.classfile.tracker;

import java.util.Map;
import java.util.Objects;

public class TrackingData {

    public final String gav;
    public final String source;
    private final Map<String, String> attributes;

    public TrackingData(String gav, String source, Map<String, String> attributes) {
        this.gav = gav;
        this.source = source;
        this.attributes = attributes;
    }

    public String getGav() {
        return gav;
    }

    public String getSource() {
        return source;
    }

    public Map<String, String> getAttributes() {
        return attributes;
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

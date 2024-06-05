package com.redhat.hacbs.classfile.tracker;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.Objects;

public class TrackingData {

    public static final Logger LOGGER = System.getLogger(TrackingData.class.getName());

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
        return Objects.equals(gav, that.gav) && Objects.equals(source, that.source)
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gav, source, attributes);
    }

    @Override
    public String toString() {
        return "TrackingData{" +
                "gav='" + gav + '\'' +
                ", source='" + source + '\'' +
                ", attributes=" + attributes +
                '}';
    }

    public static String extractClassifier(String artifact, String version, String target) {
        String jarExtension = ".jar";
        String classifier = null;
        String artifactAndVersionPrefix = artifact + "-" + version + "-";
        // Has classifier due to presence of '-' after version
        if (target.startsWith(artifactAndVersionPrefix) && target.endsWith(jarExtension)) {
            classifier = target.substring(artifactAndVersionPrefix.length(), target.length() - jarExtension.length());
            if (LOGGER.isLoggable(Level.DEBUG)) {
                LOGGER.log(Level.DEBUG, "Extracted classifier '%s' for artifact '%s' and version '%s'".formatted(classifier,
                        artifact, version));
            }
        }
        return classifier;
    }
}

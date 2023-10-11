package com.redhat.hacbs.container.analyser.build;

public record JavaVersion(String version, int intVersion) implements Comparable<JavaVersion> {
    public JavaVersion(String version) {
        this(version, toVersion(version));
    }

    public static int toVersion(String version) {
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        return Integer.parseInt(version);
    }

    @Override
    public int compareTo(JavaVersion o) {
        return Integer.compare(intVersion, o.intVersion);
    }
}

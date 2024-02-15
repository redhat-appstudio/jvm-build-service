package com.redhat.hacbs.container.analyser.build;

public record JavaVersion(String version, int intVersion) implements Comparable<JavaVersion> {

    public static final JavaVersion JAVA_7 = new JavaVersion("7");

    public static final JavaVersion JAVA_8 = new JavaVersion("8");

    public static final JavaVersion JAVA_11 = new JavaVersion("11");

    public static final JavaVersion JAVA_17 = new JavaVersion("17");

    public static final JavaVersion JAVA_21 = new JavaVersion("21");

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

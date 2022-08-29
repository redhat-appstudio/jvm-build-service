package com.redhat.hacbs.container.analyser.build;

public class JavaVersion {

    public static int toVersion(String version) {
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        return Integer.parseInt(version);
    }

}

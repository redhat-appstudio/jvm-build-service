package com.redhat.hacbs.gradle;

import java.util.Set;

import org.gradle.tooling.model.Model;

/**
 * Custom tooling model.
 */
public interface BuildInformation extends Model {
    /**
     * Gets the Java version required by the build.
     *
     * @return the Java version
     */
    int getJavaVersion();

    /**
     * Gets the plugins used by the build.
     *
     * @return the plugins
     */
    Set<String> getPlugins();
}

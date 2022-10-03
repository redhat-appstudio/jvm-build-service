package com.redhat.hacbs.gradle;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

/**
 * Implementation of the custom tooling model.
 */
public class DefaultBuildInformation implements BuildInformation, Serializable {
    private int javaVersion;

    private Set<String> plugins;

    @Override
    public int getJavaVersion() {
        return javaVersion;
    }

    /**
     * Sets the Java version
     *
     * @param javaVersion the Java version
     */
    public void setJavaVersion(int javaVersion) {
        this.javaVersion = javaVersion;
    }

    @Override
    public Set<String> getPlugins() {
        return Collections.unmodifiableSet(plugins);
    }

    /**
     * Sets the plugins
     *
     * @param plugins the plugins
     */
    public void setPlugins(Set<String> plugins) {
        this.plugins = plugins;
    }
}

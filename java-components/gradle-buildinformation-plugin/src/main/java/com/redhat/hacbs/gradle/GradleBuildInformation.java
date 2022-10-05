package com.redhat.hacbs.gradle;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;

public class GradleBuildInformation {
    private int javaVersion;

    private Set<String> plugins;

    private String gradleVersion;

    /**
     * Creates a new empty Gradle build information.
     */
    public GradleBuildInformation() {

    }

    /**
     * Creates a new Gradle build information from the given project.
     *
     * @param project the project
     */
    public GradleBuildInformation(Project project) {
        Set<Project> projects = project.getAllprojects();
        this.javaVersion = JavaVersion.VERSION_17.ordinal() + 1;
        this.plugins = new LinkedHashSet<>();

        for (Project p : projects) {
            for (Plugin<?> plugin : p.getPlugins()) {
                this.plugins.add(plugin.getClass().getName());
            }

            JavaPluginExtension javaPluginExtension = p.getExtensions().findByType(JavaPluginExtension.class);

            if (javaPluginExtension != null) {
                int sourceCompatibility = javaPluginExtension.getSourceCompatibility().ordinal() + 1;
                int targetCompatibility = javaPluginExtension.getTargetCompatibility().ordinal() + 1;
                this.javaVersion = Math.max(sourceCompatibility, targetCompatibility);
            }
        }
    }

    /**
     * Gets the Java version.
     *
     * @return the Java version
     */
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

    /**
     * Gets the plugins.
     *
     * @return the plugins
     */
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

    /**
     * Gets the Gradle version.
     *
     * @return the Gradle version
     */
    public String getGradleVersion() {
        return gradleVersion;
    }

    /**
     * Sets the Gradle version
     *
     * @param gradleVersion the Java version
     */
    public void setGradleVersion(String gradleVersion) {
        this.gradleVersion = gradleVersion;
    }
}

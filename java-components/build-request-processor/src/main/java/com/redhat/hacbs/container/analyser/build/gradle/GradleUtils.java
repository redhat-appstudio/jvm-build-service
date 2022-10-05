package com.redhat.hacbs.container.analyser.build.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.util.GradleVersion;

/**
 * Utility class for Gradle.
 */
public final class GradleUtils {
    /**
     * The default Gradle arguments.
     */
    public static final List<String> DEFAULT_GRADLE_ARGS = List.of("build", "publish");

    /**
     * Identifier for the plugin {@code com.github.sherter.google-java-format}.
     */
    public static final String GOOGLE_JAVA_FORMAT_PLUGIN = "com.github.sherter.googlejavaformatgradleplugin.GoogleJavaFormatPlugin";

    static final String BUILD_GRADLE = "build.gradle";

    static final String BUILD_GRADLE_KTS = "build.gradle.kts";

    private GradleUtils() {

    }

    public static int getMinimumSupportedJavaVersion(String gradleVersion) {
        GradleVersion version = GradleVersion.version(gradleVersion);

        if (version.compareTo(GradleVersion.version("4.0")) < 0) {
            throw new IllegalArgumentException("Unsupported Gradle version: " + gradleVersion);
        }

        if (version.compareTo(GradleVersion.version("5.0")) < 0) {
            return 7;
        }

        return 8;
    }

    /**
     * Gets the supported Java version for the given Gradle version
     *
     * @param gradleVersion the Gradle version
     * @return the supported Java version
     */
    public static int getSupportedJavaVersion(String gradleVersion) {
        GradleVersion version = GradleVersion.version(gradleVersion);

        if (version.compareTo(GradleVersion.version("7.3")) >= 0) {
            return 17;
        }

        if (version.compareTo(GradleVersion.version("5.0")) >= 0) {
            return 11;
        }

        if (version.compareTo(GradleVersion.version("4.0")) >= 0) {
            return 8;
        }

        throw new IllegalArgumentException("Unsupported Gradle version: " + gradleVersion);
    }

    /**
     * Returns true if and only if the directory contains a readable file named {@code build.gradle} or
     * {@code build.gradle.kts}.
     *
     * @param projectDir the project directory
     * @return whether the current directory contains a Gradle build
     */
    public static boolean isGradleBuild(Path projectDir) {
        var buildGradle = projectDir.resolve(BUILD_GRADLE);

        if (!Files.isRegularFile(buildGradle) || !Files.isReadable(buildGradle)) {
            var buildGradleKts = projectDir.resolve(BUILD_GRADLE_KTS);
            return (Files.isRegularFile(buildGradleKts) && Files.isReadable(buildGradleKts));
        }

        return true;
    }
}

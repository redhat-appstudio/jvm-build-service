package com.redhat.hacbs.container.analyser.build.gradle;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.gradle.util.GradleVersion;

import io.quarkus.logging.Log;

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
    public static final String GOOGLE_JAVA_FORMAT_PLUGIN = "com.github.sherter.google-java-format";

    /**
     * List of available Gradle versions in image.
     */
    public static final List<GradleVersion> AVAILABLE_GRADLE_VERSIONS = List.of(GradleVersion.version("5.4.1"),
            GradleVersion.version("7.4.1"));

    static final String BUILD_GRADLE = "build.gradle";

    static final String BUILD_GRADLE_KTS = "build.gradle.kts";

    private static final String GRADLE_WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties";

    private static final String DISTRIBUTION_URL_KEY = "distributionUrl";

    private static final Pattern GRADLE_7_3_PLUS = Pattern.compile("^7\\.[3456789].*$");

    private static final Pattern GRADLE_5_THROUGH_7 = Pattern.compile("^[567]\\..*$");

    private static final Pattern DISTRIBUTION_URL_PATTERN = Pattern.compile("^.*/gradle-(?<version>.*)-(all|bin)\\.zip$");

    private GradleUtils() {

    }

    /**
     * Gets the location of the {@code gradle-wrapper.properties} starting from the base directory
     *
     * @param basedir the base directory
     * @return the path to {@code gradle-wrapper.properties}
     */
    public static Path getPropertiesFile(Path basedir) {
        Path path = basedir.resolve(GRADLE_WRAPPER_PROPERTIES);
        Log.infof("Returning path to gradle/wrapper/gradle-wrapper.properties: %s", path);
        return path;
    }

    /**
     * Get the Gradle version from {@code gradle/wrapper/gradle-wrapper.properties}.
     *
     * @param propertiesFile the location of {@code gradle/wrapper/gradle-wrapper.properties}
     * @return the Gradle version
     */
    public static Optional<String> getGradleVersionFromWrapperProperties(Path propertiesFile) {
        try (Reader reader = Files.newBufferedReader(propertiesFile)) {
            var properties = new Properties();
            properties.load(reader);
            return getGradleVersionFromWrapperProperties(properties);
        } catch (IOException e) {
            Log.errorf("Error reading gradle-wrapper.properties: %s", e.getMessage(), e);
            return Optional.empty();
        }
    }

    private static Optional<String> getGradleVersionFromWrapperProperties(Properties properties) {
        var distributionUrl = properties.getProperty(DISTRIBUTION_URL_KEY);
        var matcher = DISTRIBUTION_URL_PATTERN.matcher(distributionUrl);
        return matcher.matches() ? Optional.of(matcher.group("version")) : Optional.empty();
    }

    /**
     * Find the nearest available Gradle version to the given version. If possible, return the nearest version that is
     * less than or equal to the given version. If the given version is null or empty, return the latest available
     * version.
     *
     * @param version the version
     * @return the nearest Gradle version
     */
    public static String findNearestGradleVersion(String version) {
        if (version == null || version.isEmpty()) {
            return AVAILABLE_GRADLE_VERSIONS.get(AVAILABLE_GRADLE_VERSIONS.size() - 1).getVersion();
        }

        GradleVersion gradleVersion = GradleVersion.version(version);
        int ret = Collections.binarySearch(AVAILABLE_GRADLE_VERSIONS, gradleVersion);
        int index = ret >= 0 ? ret : Math.max(0, (-ret) - 2);
        return AVAILABLE_GRADLE_VERSIONS.get(index).getVersion();
    }

    /**
     * Gets the supported Java version for the given Gradle version
     *
     * @param gradleVersion the Gradle version
     * @return the supported Java version
     */
    public static String getSupportedJavaVersion(String gradleVersion) {
        if (GRADLE_7_3_PLUS.matcher(gradleVersion).matches()) {
            return "17";
        }

        if (GRADLE_5_THROUGH_7.matcher(gradleVersion).matches()) {
            return "11";
        }

        if (gradleVersion.startsWith("4.")) {
            return "8";
        }

        throw new IllegalArgumentException("Unsupported Gradle version: " + gradleVersion);
    }

    /**
     * Returns true if and only if the directory contains a readable file named {@code build.gradle} or
     * {@code build.gradle.kts}.
     *
     * @param basedir the base directory
     * @return whether the current directory contains a Gradle build
     */
    public static boolean isGradleBuild(Path basedir) {
        var buildGradle = basedir.resolve(BUILD_GRADLE);

        if (!Files.isRegularFile(buildGradle) || !Files.isReadable(buildGradle)) {
            var buildGradleKts = basedir.resolve(BUILD_GRADLE_KTS);
            return (Files.isRegularFile(buildGradleKts) && Files.isReadable(buildGradleKts));
        }

        return true;
    }

    /**
     * Checks whether the build file in the path contains the given character sequence.
     *
     * @param path the path to check
     * @param csq the character sequence to check for
     * @return whether the character sequence is found in a build file located directly under the path
     */
    public static boolean isInBuildGradle(Path path, CharSequence csq) {
        if (!Files.isDirectory(path)) {
            return false;
        }

        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile).filter(Files::isReadable)
                    .filter(p -> p.getFileName().toString()
                            .equals(BUILD_GRADLE) || p.getFileName().toString().equals(BUILD_GRADLE_KTS))
                    .flatMap(path1 -> {
                        try {
                            return Files.lines(path1);
                        } catch (IOException e) {
                            return Stream.empty();
                        }
                    }).anyMatch(s1 -> s1.contains(csq));
        } catch (IOException e) {
            return false;
        }
    }
}

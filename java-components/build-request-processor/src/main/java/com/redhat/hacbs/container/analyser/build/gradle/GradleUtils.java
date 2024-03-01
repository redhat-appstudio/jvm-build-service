package com.redhat.hacbs.container.analyser.build.gradle;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkus.logging.Log;

/**
 * Utility class for Gradle.
 */
public final class GradleUtils {

    /**
     * Format for applying plugins.
     */
    public static final String PLUGIN_FORMAT = "^\\s*(" + "apply\\s*\\(\\s*plugin:\\s*[\"']%s[\"']\\s*\\)\\s*;?" + "|"
            + "apply\\s+plugin:\\s*[\"']%s[\"']\\s*;?" + ")";

    /**
     * Code for applying the plugin {@code maven}.
     */
    public static final String MAVEN_PLUGIN = String.format(PLUGIN_FORMAT, "maven", "maven");

    /**
     * Code for applying the plugin {@code maven-publish}.
     */
    public static final String MAVEN_PUBLISH_PLUGIN = String.format(PLUGIN_FORMAT, "maven-publish", "maven-publish");

    static final String BUILD_GRADLE = "build.gradle";

    static final String BUILD_GRADLE_KTS = "build.gradle.kts";

    private static final String GRADLE_WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties";
    private static final String OLD_GRADLE_WRAPPER_PROPERTIES = ".gradle-wrapper/gradle-wrapper.properties";

    private static final String DISTRIBUTION_URL_KEY = "distributionUrl";

    private static final Pattern GRADLE_7_3_PLUS = Pattern.compile("^8\\.[0-9].*$|^7\\.[3456789].*$");

    private static final Pattern GRADLE_5_THROUGH_7 = Pattern.compile("^[567]\\..*$");

    private static final Pattern DISTRIBUTION_URL_PATTERN = Pattern.compile("^.*/gradle-(?<version>.*)-(all|bin)\\.zip$");

    private static final Pattern VERSION_PATTERN = Pattern
            .compile("((\\d+)(\\.\\d+)+)(-(\\p{Alpha}+)-(\\w+))?(-(SNAPSHOT|\\d{14}([-+]\\d{4})?))?");

    /**
     * Default Gradle arguments.
     */
    public static final List<String> DEFAULT_GRADLE_ARGS = List.of("build", "publishToMavenLocal");

    /**
     * Gradle arguments if 'maven' plugin is detected.
     */
    public static final List<String> MAVEN_PLUGIN_GRADLE_ARGS = List.of("build", "uploadArchives");

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
        if (!Files.exists(path)) {
            var old = basedir.resolve(OLD_GRADLE_WRAPPER_PROPERTIES);
            if (Files.exists(old)) {
                path = old;
            }
        }
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

    private static int getMajorVersion(String version) {
        Matcher matcher = VERSION_PATTERN.matcher(version);

        if (!matcher.matches()) {
            return -1;
        }

        return Integer.parseInt(matcher.group(2));
    }

    /**
     * Gets the Java version from the Gradle build files, if any, and matches it with a supported Java version, if
     * possible.
     *
     * @param buildFile the build file to check
     * @return the specified Java version, or empty if none
     * @throws IOException if an error occurs while reading from the build files
     */
    public static String getSpecifiedJavaVersion(Path buildFile) throws IOException {
        if (isInBuildGradle(buildFile,
                "^\\s*(source|target)Compatibility\\s*=\\s*(JavaVersion\\.VERSION_)?['\"]?1[2-7][^0-9]['\"]?")) {
            return "17";
        }

        if (isInBuildGradle(buildFile,
                "^\\s*(source|target)Compatibility\\s*=\\s*(JavaVersion\\.VERSION_)?['\"]?(1[._])?(9|1[0-1])[^0-9]['\"]?")) {
            return "11";
        }

        if (isInBuildGradle(buildFile,
                "^\\s*(source|target)Compatibility\\s*=\\s*(JavaVersion\\.VERSION_)?['\"]?(1[._])?[1-8][^0-9]['\"]?")) {
            return "8";
        }

        return "";
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
     * Returns the path if and only if the directory contains a readable file named {@code build.gradle} or
     * {@code build.gradle.kts}.
     *
     * @param basedir the base directory
     * @return whether the current directory contains a Gradle build
     */
    public static Optional<Path> getGradleBuild(Path basedir) {
        var buildGradle = basedir.resolve(BUILD_GRADLE);

        if (Files.isRegularFile(buildGradle)) {
            return Optional.of(buildGradle);
        }

        var buildGradleKts = basedir.resolve(BUILD_GRADLE_KTS);

        if (Files.isRegularFile(buildGradleKts)) {
            return Optional.of(buildGradleKts);
        }

        return Optional.empty();
    }

    /**
     * Checks whether the build file in the path contains the given regex.
     *
     * @param buildFile the build file to check
     * @param regex the regular expression to match
     * @return whether the character sequence is found in a build file located directly under the path
     * @throws IOException if an error occurs while reading from the file
     */
    public static boolean isInBuildGradle(Path buildFile, String regex) throws IOException {
        try {
            var content = Files.readString(buildFile);
            return Pattern.compile(regex, Pattern.DOTALL | Pattern.MULTILINE).matcher(content).find();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get the appropriate Gradle arguments for the given build file.
     *
     * @param buildFile the build file to check
     * @return the Gradle arguments
     * @throws IOException if an error occurs while reading from the build file(s)
     */
    public static List<String> getGradleArgs(Path buildFile) throws IOException {
        boolean hasMavenPlugin = isInBuildGradle(buildFile, MAVEN_PLUGIN);
        boolean hasMavenPublishPlugin = isInBuildGradle(buildFile, MAVEN_PUBLISH_PLUGIN);

        if (hasMavenPlugin && hasMavenPublishPlugin) {
            return DEFAULT_GRADLE_ARGS;
        } else if (hasMavenPlugin) {
            return MAVEN_PLUGIN_GRADLE_ARGS;
        } else {
            return DEFAULT_GRADLE_ARGS;
        }
    }
}

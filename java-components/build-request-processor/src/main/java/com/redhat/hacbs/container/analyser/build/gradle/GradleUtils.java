package com.redhat.hacbs.container.analyser.build.gradle;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Utility class for Gradle.
 */
public final class GradleUtils {
    /**
     * The default Gradle arguments.
     */
    public static final List<String> DEFAULT_GRADLE_ARGS = List.of("-Prelease", "build", "publish");

    /**
     * The default Gradle version.
     */
    public static final String DEFAULT_GRADLE_VERSION = "7.5";

    /**
     * Identifier for the plugin {@code com.github.sherter.google-java-format}.
     */
    public static final String GOOGLE_JAVA_FORMAT_PLUGIN = "com.github.sherter.google-java-format";

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
     * Get the Gradle version from {@code gradle/wrapper/gradle-wrapper.properties}.
     *
     * @return the Gradle version
     */
    public static Optional<String> getGradleVersionFromWrapperProperties() {
        return getGradleVersionFromWrapperProperties(getPropertiesFile());
    }

    private static Optional<String> getGradleVersionFromWrapperProperties(Properties properties) {
        var distributionUrl = properties.getProperty(DISTRIBUTION_URL_KEY);
        var matcher = DISTRIBUTION_URL_PATTERN.matcher(distributionUrl);
        return matcher.matches() ? Optional.of(matcher.group("version")) : Optional.empty();
    }

    static Optional<String> getGradleVersionFromWrapperProperties(Path propertiesFile) {
        try (Reader reader = Files.newBufferedReader(propertiesFile)) {
            var properties = new Properties();
            properties.load(reader);
            return getGradleVersionFromWrapperProperties(properties);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Path getPropertiesFile() {
        return Path.of(GRADLE_WRAPPER_PROPERTIES);
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

    static boolean isGradleBuild(Path basedir) {
        var buildGradle = basedir.resolve(BUILD_GRADLE);

        if (!Files.isRegularFile(buildGradle) || !Files.isReadable(buildGradle)) {
            var buildGradleKts = basedir.resolve(BUILD_GRADLE_KTS);
            return (Files.isRegularFile(buildGradleKts) && Files.isReadable(buildGradleKts));
        }

        return true;
    }

    /**
     * Returns true if and only if the current directory contains a readable file named {@code build.gradle} or
     * {@code build.gradle.kts}.
     *
     * @return whether the current directory contains a Gradle build
     */
    public static boolean isGradleBuild() {
        return isGradleBuild(Path.of("."));
    }

    /**
     * Find a string in Gradle build giles starting from the current directory.
     *
     * @param csq the string to find
     * @return whether the string is found
     */
    public static boolean isInBuildGradle(CharSequence csq) {
        return isInBuildGradle(Path.of("."), csq);
    }

    static boolean isInBuildGradle(Path path, CharSequence csq) {
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

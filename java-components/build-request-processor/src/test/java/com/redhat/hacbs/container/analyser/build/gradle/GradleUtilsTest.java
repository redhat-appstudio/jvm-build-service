package com.redhat.hacbs.container.analyser.build.gradle;

import static com.redhat.hacbs.container.analyser.build.gradle.GradleUtils.DEFAULT_GRADLE_ARGS;
import static com.redhat.hacbs.container.analyser.build.gradle.GradleUtils.GOOGLE_JAVA_FORMAT_PLUGIN;
import static com.redhat.hacbs.container.analyser.build.gradle.GradleUtils.MAVEN_PLUGIN;
import static com.redhat.hacbs.container.analyser.build.gradle.GradleUtils.MAVEN_PLUGIN_GRADLE_ARGS;
import static com.redhat.hacbs.container.analyser.build.gradle.GradleUtils.MAVEN_PUBLISH_PLUGIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GradleUtilsTest {
    private static final String GRADLE_WRAPPER_PROPERTIES = "gradle-wrapper.properties";

    @Test
    void testGetGradleVersion() throws URISyntaxException {
        URL url = GradleUtilsTest.class.getResource(GRADLE_WRAPPER_PROPERTIES);
        assertThat(url).isNotNull();
        URI uri = url.toURI();
        Path propertiesFile = Path.of(uri);
        assertThat(GradleUtils.getGradleVersionFromWrapperProperties(propertiesFile)).get()
                .isEqualTo("7.6-20220622230534+0000");
    }

    @Test
    void testIsGradleBuild(@TempDir Path basedir) throws IOException {
        assertThat(GradleUtils.isGradleBuild(basedir)).isFalse();
        Path buildGradle = basedir.resolve(GradleUtils.BUILD_GRADLE);
        Files.createFile(buildGradle);
        assertThat(GradleUtils.isGradleBuild(basedir)).isTrue();
        Files.delete(buildGradle);
        assertThat(buildGradle).doesNotExist();
        Path buildGradleKts = basedir.resolve(GradleUtils.BUILD_GRADLE_KTS);
        Files.createFile(buildGradleKts);
        assertThat(GradleUtils.isGradleBuild(basedir)).isTrue();
        Files.delete(buildGradleKts);
        assertThat(buildGradleKts).doesNotExist();
    }

    @Test
    void testSpecifiedJavaVersion(@TempDir Path basedir) throws IOException {
        Path buildGradle = basedir.resolve(GradleUtils.BUILD_GRADLE);
        Files.writeString(buildGradle, "sourceCompatibility = 1.6" + System.lineSeparator()
                + "targetCompatibility = 1.6" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEqualTo("8");
        Files.writeString(buildGradle, "sourceCompatibility = JavaVersion.VERSION_1_7;" + System.lineSeparator()
                + "targetCompatibility = JavaVersion.VERSION_1_7;" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEqualTo("8");
        Files.writeString(buildGradle, "sourceCompatibility = JavaVersion.VERSION_1_8" + System.lineSeparator()
                + "targetCompatibility = JavaVersion.VERSION_1_8" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEqualTo("8");
        Files.writeString(buildGradle, "sourceCompatibility = JavaVersion.VERSION_1_8.toString()" + System.lineSeparator()
                + "targetCompatibility = JavaVersion.VERSION_1_8.toString()" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEqualTo("8");
        Files.writeString(buildGradle, "sourceCompatibility = 1.8" + System.lineSeparator()
                + "targetCompatibility = 1.8" + System.lineSeparator());
        Files.writeString(buildGradle, "sourceCompatibility = 8" + System.lineSeparator()
                + "targetCompatibility = 8" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEqualTo("8");
        Files.writeString(buildGradle, "sourceCompatibility = 9" + System.lineSeparator()
                + "targetCompatibility = 9" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEqualTo("11");
        Files.writeString(buildGradle, "sourceCompatibility = JavaVersion.VERSION_11" + System.lineSeparator()
                + "targetCompatibility = JavaVersion.VERSION_11" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEqualTo("11");
        Files.writeString(buildGradle, "sourceCompatibility = minJavaVersion" + System.lineSeparator()
                + "targetCompatibility = minJavaVersion" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEmpty();
        Files.writeString(buildGradle, "sourceCompatibility = JavaVersion.VERSION_17" + System.lineSeparator()
                + "targetCompatibility = JavaVersion.VERSION_17" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEqualTo("17");
        Files.writeString(buildGradle, "sourceCompatibility = JavaVersion.VERSION_18" + System.lineSeparator()
                + "targetCompatibility = JavaVersion.VERSION_18" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEmpty();
        Files.writeString(buildGradle, "sourceCompatibility = JavaVersion.VERSION_20" + System.lineSeparator()
                + "targetCompatibility = JavaVersion.VERSION_20" + System.lineSeparator());
        assertThat(GradleUtils.getSpecifiedJavaVersion(buildGradle)).isEmpty();
    }

    @Test
    void testSupportedJavaVersion() {
        assertThatThrownBy(() -> GradleUtils.getSupportedJavaVersion("3.0"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThat(GradleUtils.getSupportedJavaVersion("4.0")).isEqualTo("8");
        assertThat(GradleUtils.getSupportedJavaVersion("5.0")).isEqualTo("11");
        assertThat(GradleUtils.getSupportedJavaVersion("6.0")).isEqualTo("11");
        assertThat(GradleUtils.getSupportedJavaVersion("7.0")).isEqualTo("11");
        assertThat(GradleUtils.getSupportedJavaVersion("7.3")).isEqualTo("17");
        assertThat(GradleUtils.getSupportedJavaVersion("8.0")).isEqualTo("17");
    }

    @Test
    void testFindGoogleJavaFormatPlugin(@TempDir Path basedir) throws IOException {
        Path buildGradle = basedir.resolve(GradleUtils.BUILD_GRADLE);
        Files.writeString(buildGradle, "plugins {" + System.lineSeparator() + "  id '" + GOOGLE_JAVA_FORMAT_PLUGIN
                + "' version '0.9'" + System.lineSeparator() + "}" + System.lineSeparator());
        assertThat(GradleUtils.isInBuildGradle(basedir, GOOGLE_JAVA_FORMAT_PLUGIN)).isTrue();
        assertThat(GradleUtils.isInBuildGradle(basedir, MAVEN_PLUGIN)).isFalse();
    }

    @Test
    void testFindMavenPlugin(@TempDir Path basedir) throws IOException {
        Path buildGradle = basedir.resolve(GradleUtils.BUILD_GRADLE);
        Files.writeString(buildGradle, "apply(plugin: \"maven\");" + System.lineSeparator());
        assertThat(GradleUtils.isInBuildGradle(basedir, MAVEN_PLUGIN)).isTrue();
        assertThat(GradleUtils.isInBuildGradle(basedir, GOOGLE_JAVA_FORMAT_PLUGIN)).isFalse();
        assertThat(GradleUtils.getGradleArgs(buildGradle)).isEqualTo(MAVEN_PLUGIN_GRADLE_ARGS);
        Files.writeString(buildGradle, "apply plugin: 'maven'" + System.lineSeparator());
        assertThat(GradleUtils.isInBuildGradle(basedir, MAVEN_PLUGIN)).isTrue();
        assertThat(GradleUtils.isInBuildGradle(basedir, GOOGLE_JAVA_FORMAT_PLUGIN)).isFalse();
        assertThat(GradleUtils.getGradleArgs(buildGradle)).isEqualTo(MAVEN_PLUGIN_GRADLE_ARGS);
        Files.writeString(buildGradle, "apply(plugin: \"maven-publish\");" + System.lineSeparator());
        assertThat(GradleUtils.isInBuildGradle(basedir, MAVEN_PUBLISH_PLUGIN)).isTrue();
        assertThat(GradleUtils.isInBuildGradle(basedir, MAVEN_PLUGIN)).isFalse();
        assertThat(GradleUtils.getGradleArgs(buildGradle)).isEqualTo(DEFAULT_GRADLE_ARGS);
        Files.writeString(buildGradle, "apply plugin: 'maven-publish'" + System.lineSeparator());
        assertThat(GradleUtils.isInBuildGradle(basedir, MAVEN_PUBLISH_PLUGIN)).isTrue();
        assertThat(GradleUtils.isInBuildGradle(basedir, MAVEN_PLUGIN)).isFalse();
        assertThat(GradleUtils.getGradleArgs(buildGradle)).isEqualTo(DEFAULT_GRADLE_ARGS);
    }

    @Test
    void testFindMavenPluginMultiple(@TempDir Path basedir) throws IOException {
        Path buildGradle = basedir.resolve(GradleUtils.BUILD_GRADLE);
        Files.writeString(buildGradle, """
                    apply plugin: "maven"
                    apply plugin: "signing"
                    apply plugin: "maven-publish"
                """.indent(8));
        assertThat(GradleUtils.isInBuildGradle(basedir, MAVEN_PLUGIN)).isTrue();
        assertThat(GradleUtils.isInBuildGradle(basedir, MAVEN_PUBLISH_PLUGIN)).isTrue();
        assertThat(GradleUtils.isInBuildGradle(basedir, GOOGLE_JAVA_FORMAT_PLUGIN)).isFalse();
        assertThat(GradleUtils.getGradleArgs(buildGradle)).isEqualTo(DEFAULT_GRADLE_ARGS);
    }
}

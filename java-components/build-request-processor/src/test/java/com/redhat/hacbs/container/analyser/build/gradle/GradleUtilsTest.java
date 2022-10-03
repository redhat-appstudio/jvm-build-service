package com.redhat.hacbs.container.analyser.build.gradle;

import static com.redhat.hacbs.container.analyser.build.gradle.GradleUtils.GOOGLE_JAVA_FORMAT_PLUGIN;
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

import com.redhat.hacbs.gradle.BuildInformationPlugin;
import com.redhat.hacbs.gradle.GradleBuildInformation;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GradleUtilsTest {
    private static final String GRADLE_WRAPPER_PROPERTIES = "/gradle/wrapper/gradle-wrapper.properties";

    @Test
    void testGetGradleVersion() throws URISyntaxException {
        URL url = GradleUtilsTest.class.getResource(GRADLE_WRAPPER_PROPERTIES);
        assertThat(url).isNotNull();
        URI uri = url.toURI();
        Path propertiesFile = Path.of(uri);
        Path projectDir = propertiesFile.getParent().getParent().getParent();
        GradleBuildInformation gradleBuildInformation = BuildInformationPlugin.getBuildInformation(projectDir);
        assertThat(gradleBuildInformation.getGradleVersion()).isEqualTo("7.4");
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
    void testGetMinimumSupportedJavaVersion() {
        assertThatThrownBy(() -> GradleUtils.getSupportedJavaVersion("3.0"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThat(GradleUtils.getMinimumSupportedJavaVersion("4.0")).isEqualTo(7);
        assertThat(GradleUtils.getMinimumSupportedJavaVersion("5.0")).isEqualTo(8);
        assertThat(GradleUtils.getMinimumSupportedJavaVersion("6.0")).isEqualTo(8);
        assertThat(GradleUtils.getMinimumSupportedJavaVersion("7.0")).isEqualTo(8);
        assertThat(GradleUtils.getMinimumSupportedJavaVersion("8.0")).isEqualTo(8);
    }

    @Test
    void testGetSupportedJavaVersion() {
        assertThatThrownBy(() -> GradleUtils.getSupportedJavaVersion("3.0"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        assertThat(GradleUtils.getSupportedJavaVersion("4.0")).isEqualTo(8);
        assertThat(GradleUtils.getSupportedJavaVersion("5.0")).isEqualTo(11);
        assertThat(GradleUtils.getSupportedJavaVersion("6.0")).isEqualTo(11);
        assertThat(GradleUtils.getSupportedJavaVersion("7.0")).isEqualTo(11);
        assertThat(GradleUtils.getSupportedJavaVersion("7.3")).isEqualTo(17);
        assertThat(GradleUtils.getSupportedJavaVersion("8.0")).isEqualTo(17);
    }

    @Test
    void testGetPlugins(@TempDir Path projectDir) throws IOException {
        Path buildGradle = projectDir.resolve(GradleUtils.BUILD_GRADLE);
        Files.writeString(buildGradle, """
                plugins {
                    id 'com.github.sherter.google-java-format' version '0.9'
                }

                repositories {
                    mavenCentral()
                }
                """);
        GradleBuildInformation gradleBuildInformation = BuildInformationPlugin.getBuildInformation(projectDir);
        assertThat(gradleBuildInformation.getPlugins()).contains(GOOGLE_JAVA_FORMAT_PLUGIN);
    }
}

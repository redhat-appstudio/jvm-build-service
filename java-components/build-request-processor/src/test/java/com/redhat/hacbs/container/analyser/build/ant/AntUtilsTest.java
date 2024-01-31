package com.redhat.hacbs.container.analyser.build.ant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AntUtilsTest {
    private static final String BUILD_XML = "build.xml";

    private static final String PROJECT_NAME = "test";

    private static final int JAVA_VERSION = 8;

    private static Path basedir;

    @BeforeAll
    static void loadProject() throws URISyntaxException {
        var url = AntUtilsTest.class.getResource(BUILD_XML);
        assertThat(url).isNotNull();
        var uri = url.toURI();
        var buildFile = Path.of(uri);
        basedir = buildFile.getParent();
    }

    @Test
    void testIsAntBuild() {
        assertThat(AntUtils.getAntBuild(basedir)).hasValue(basedir.resolve(AntUtils.BUILD_XML));
    }

    @Test
    void testLoadProject() {
        var buildFile = basedir.resolve(BUILD_XML);
        var project = AntUtils.loadProject(buildFile);
        var projectName = project.getName();
        assertThat(projectName).isEqualTo(PROJECT_NAME);
    }

    @Test
    void testGetJavaVersion() {
        assertThat(AntUtils.getAntBuild(basedir)).hasValueSatisfying(
                buildXml -> assertThat(AntUtils.getJavaVersion(buildXml)).isEqualTo(Integer.toString(JAVA_VERSION)));
    }

    @Test
    void testGetJavaVersionRange() {
        assertThat(AntUtils.getAntBuild(basedir)).hasValueSatisfying(
                buildXml -> assertThat(AntUtils.getJavaVersionRange(buildXml)).extracting("min", "max", "preferred")
                        .containsExactly("8", "8", "8"));
    }

    @Test
    void testGetAntVersionForJavaVersion() {
        var antVersionForJava5 = AntUtils.getAntVersionForJavaVersion("5");
        assertThat(antVersionForJava5).isEqualTo(AntUtils.ANT_VERSION_JAVA5);
        var antVersionForJava6 = AntUtils.getAntVersionForJavaVersion("6");
        assertThat(antVersionForJava6).isEqualTo(AntUtils.ANT_VERSION_JAVA5);
        var antVersionForJava7 = AntUtils.getAntVersionForJavaVersion("7");
        assertThat(antVersionForJava7).isEqualTo(AntUtils.ANT_VERSION_JAVA5);
        var antVersionForJava8 = AntUtils.getAntVersionForJavaVersion("8");
        assertThat(antVersionForJava8).isEqualTo(AntUtils.ANT_VERSION_JAVA8);
        var antVersionForJava11 = AntUtils.getAntVersionForJavaVersion("11");
        assertThat(antVersionForJava11).isEqualTo(AntUtils.ANT_VERSION_JAVA8);
        var antVersionForJava17 = AntUtils.getAntVersionForJavaVersion("17");
        assertThat(antVersionForJava17).isEqualTo(AntUtils.ANT_VERSION_JAVA8);
        var antVersionForJavaEmpty = AntUtils.getAntVersionForJavaVersion("");
        assertThat(antVersionForJavaEmpty).isEqualTo(AntUtils.ANT_VERSION_JAVA8);
    }

    @Test
    void testGetAntArgs() {
        var antArgs = AntUtils.getAntArgs();
        assertThat(antArgs).contains("-v");
    }
}

package com.redhat.hacbs.container.verifier;

import static org.apache.maven.settings.MavenSettingsBuilder.ALT_USER_SETTINGS_XML_LOCATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import picocli.CommandLine;

//@QuarkusTest
public class VerifyBuiltArtifactsCommandTest {
    private Properties properties;

    @BeforeEach
    void backup() {
        properties = new Properties();
        properties.putAll(System.getProperties());
    }

    @AfterEach
    void restore() {
        System.setProperties(properties);
    }

    @Test
    @ResourceLock(value = SYSTEM_PROPERTIES, mode = READ_WRITE)
    void testCommand() throws URISyntaxException {
        var url = VerifyBuiltArtifactsCommandTest.class.getResource("/verifier/settings.xml");
        assertThat(url).isNotNull();
        var uri = url.toURI();
        var file = Path.of(uri);
        System.setProperty(ALT_USER_SETTINGS_XML_LOCATION, file.toAbsolutePath().toString());
        var file2 = file.getParent().resolve("repo");
        var args = new String[] { "-r", "https://repo1.maven.org/maven2", "--deploy-path", file2.toString() };
        var exitCode = new CommandLine(new VerifyBuiltArtifactsCommand()).execute(args);
        assertThat(exitCode).isZero();
    }

    @Test
    void testExcludes() throws URISyntaxException {
        var url = VerifyBuiltArtifactsCommandTest.class.getResource("/verifier/excludes/1/bar-1.0.jar");
        assertThat(url).isNotNull();
        var uri = url.toURI();
        var origFile = Path.of(uri);
        var url2 = VerifyBuiltArtifactsCommandTest.class.getResource("/verifier/excludes/2/bar-1.0.jar");
        assertThat(url2).isNotNull();
        var uri2 = url2.toURI();
        var newFile = Path.of(uri2);
        var url3 = VerifyBuiltArtifactsCommandTest.class.getResource("/verifier/excludes/excludes.txt");
        assertThat(url3).isNotNull();
        var uri3 = url3.toURI();
        var excludesFile = Path.of(uri3);
        var args = new String[] { "-of", origFile.toString(), "-nf", newFile.toString(),
                "--excludes", "+:.*[^:]:class:.*", "--excludes-file", excludesFile.toString() };
        var exitCode = new CommandLine(new VerifyBuiltArtifactsCommand()).execute(args);
        assertThat(exitCode).isEqualTo(5);
    }
}

package com.redhat.hacbs.container.verifier;

import static org.apache.maven.settings.MavenSettingsBuilder.ALT_USER_SETTINGS_XML_LOCATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import picocli.CommandLine;

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
    void testCommand() throws Exception {
        var localJunit = junitJar();
        var url = VerifyBuiltArtifactsCommandTest.class.getResource("/verifier/settings.xml");
        Path mavenRepo = localJunit;
        while (!mavenRepo.getFileName().toString().equals("org")) {
            mavenRepo = mavenRepo.getParent();
        }
        mavenRepo = mavenRepo.getParent();

        //create a single jar repo with just junit
        Path tempMavenRepo = Files.createTempDirectory("test");
        Path tempJunitDir = tempMavenRepo.resolve(mavenRepo.relativize(localJunit.getParent()));
        Files.createDirectories(tempJunitDir);
        Path relativePath = mavenRepo.relativize(localJunit);
        Path copied = tempMavenRepo.resolve(relativePath);
        Files.copy(localJunit, copied);

        assertThat(url).isNotNull();
        var uri = url.toURI();
        var file = Path.of(uri);
        System.setProperty(ALT_USER_SETTINGS_XML_LOCATION, file.toAbsolutePath().toString());
        var args = new String[] { "-r", "https://repo1.maven.org/maven2", "--deploy-path", tempMavenRepo.toString() };
        var exitCode = new CommandLine(new VerifyBuiltArtifactsCommand()).execute(args);
        assertThat(exitCode).isZero();

        //now modify junit and add an extra class
        Files.delete(copied);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(copied))) {
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(localJunit))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    out.putNextEntry(entry);
                    out.write(in.readAllBytes());
                }
                //now add this class as an extra class
                var thisClassData = VerifyBuiltArtifactsCommandTest.class
                        .getResourceAsStream(VerifyBuiltArtifactsCommandTest.class.getSimpleName() + ".class").readAllBytes();
                entry = new ZipEntry(VerifyBuiltArtifactsCommandTest.class.getName().replace(".", "/") + ".class");
                entry.setSize(thisClassData.length);
                out.putNextEntry(entry);
                out.write(thisClassData);
            }
        }

        args = new String[] { "-r", "https://repo1.maven.org/maven2", "--deploy-path", tempMavenRepo.toString() };
        exitCode = new CommandLine(new VerifyBuiltArtifactsCommand()).execute(args);
        assertThat(exitCode).isNotZero();

        //now try with excludes

        args = new String[] { "-r", "https://repo1.maven.org/maven2", "--deploy-path", tempMavenRepo.toString(), "--excludes",
                "+:.*[^:]:class:" + VerifyBuiltArtifactsCommandTest.class.getName().replace(".", "/") };
        exitCode = new CommandLine(new VerifyBuiltArtifactsCommand()).execute(args);
        assertThat(exitCode).isZero();

    }

    static Path junitJar() {
        var path = Thread.currentThread().getContextClassLoader()
                .getResource(Test.class.getName().replace(".", "/") + ".class");
        String file = path.getFile();
        return Path.of(file.substring("file:".length(), file.indexOf("!")));
    }
}

package com.redhat.hacbs.container.verifier;

import static com.redhat.hacbs.container.verifier.MavenUtils.coordsToGav;
import static com.redhat.hacbs.container.verifier.MavenUtils.coordsToPath;
import static com.redhat.hacbs.container.verifier.VerifyBuiltArtifactsCommand.CLASS_REMOVED_PATTERN;
import static com.redhat.hacbs.container.verifier.VerifyBuiltArtifactsCommand.CLASS_VERSION_CHANGED_PATTERN;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

public class VerifyBuiltArtifactsCommandTest {
    private static final String REPOSITORY_URL = "https://repo1.maven.org/maven2";

    @Test
    void testCommand() throws Exception {
        var localJunit = junitJar();
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

        var args = new String[] { "-r", REPOSITORY_URL, "--deploy-path", tempMavenRepo.toString() };
        VerifyBuiltArtifactsCommand verifyBuiltArtifactsCommand = new VerifyBuiltArtifactsCommand();
        var exitCode = new CommandLine(verifyBuiltArtifactsCommand).execute(args);
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
                try (var is = VerifyBuiltArtifactsCommandTest.class
                        .getResourceAsStream(VerifyBuiltArtifactsCommandTest.class.getSimpleName() + ".class")) {
                    assertThat(is).isNotNull();
                    var thisClassData = is.readAllBytes();
                    entry = new ZipEntry(VerifyBuiltArtifactsCommandTest.class.getName().replace(".", "/") + ".class");
                    entry.setSize(thisClassData.length);
                    out.putNextEntry(entry);
                    out.write(thisClassData);
                }
            }
        }

        args = new String[] { "-r", REPOSITORY_URL, "--deploy-path", tempMavenRepo.toString() };
        exitCode = new CommandLine(verifyBuiltArtifactsCommand).execute(args);
        assertThat(exitCode).isNotZero();

        //now try with excludes

        args = new String[] { "-r", REPOSITORY_URL, "--deploy-path", tempMavenRepo.toString(), "--excludes",
                "+:.*[^:]:class:" + VerifyBuiltArtifactsCommandTest.class.getName().replace(".", "/") };
        exitCode = new CommandLine(verifyBuiltArtifactsCommand).execute(args);
        assertThat(exitCode).isZero();
    }

    private static Path junitJar() {
        var path = Thread.currentThread().getContextClassLoader()
                .getResource(Test.class.getName().replace(".", "/") + ".class");
        assertThat(path).isNotNull();
        String file = path.getFile();
        return Path.of(URI.create(file.substring(0, file.indexOf('!'))));
    }

    @Test
    void testClassVersionChanged() {
        var input = "^:auto-value-1.7.jar:autovalue.shaded.com.google$.j2objc.annotations.$RetainedWith:version:49.0>52.0";
        var matcher = CLASS_VERSION_CHANGED_PATTERN.matcher(input);
        assertThat(matcher).matches();
        var fileName = matcher.group("fileName");
        assertThat(fileName).isNotNull().isEqualTo("auto-value-1.7.jar");
        var className = matcher.group("className");
        assertThat(className).isNotNull().isEqualTo("autovalue.shaded.com.google$.j2objc.annotations.$RetainedWith");
        var fromVersion = matcher.group("fromVersion");
        assertThat(fromVersion).isNotNull().isEqualTo("49.0");
        var toVersion = matcher.group("toVersion");
        assertThat(toVersion).isNotNull().isEqualTo("52.0");
    }

    @Test
    void testClassRemoved() {
        var input = "-:smallrye-common-classloader-1.6.0.jar:class:io/smallrye/common/classloader/ClassDefiner";
        var matcher = CLASS_REMOVED_PATTERN.matcher(input);
        assertThat(matcher).matches();
        var fileName = matcher.group("fileName");
        assertThat(fileName).isNotNull().isEqualTo("smallrye-common-classloader-1.6.0.jar");
        var className = matcher.group("className");
        assertThat(className).isNotNull().isEqualTo("io/smallrye/common/classloader/ClassDefiner");
    }

    @Test
    void testResolveArtifact() throws IOException {
        var coords = "org.mock-server:mockserver-netty:jar:jar-with-dependencies:5.8.1";
        var gav = coordsToGav(coords);
        assertThat(gav).extracting("groupId", "artifactId", "extension", "classifier", "version")
                .containsExactly("org.mock-server", "mockserver-netty", "jar", "jar-with-dependencies", "5.8.1");
        var verifyBuiltArtifactsCommand = new VerifyBuiltArtifactsCommand();
        verifyBuiltArtifactsCommand.options.mavenOptions.repositoryUrl = REPOSITORY_URL;
        var optPath = verifyBuiltArtifactsCommand.resolveArtifact(coordsToPath(coords));
        assertThat(optPath).hasValueSatisfying(path -> assertThat(path).isRegularFile().hasFileName(
                gav.getArtifactId() + "-" + gav.getVersion() + "-" + gav.getClassifier() + "." + gav.getExtension()));
        var verifyBuiltArtifactsCommand2 = new VerifyBuiltArtifactsCommand();
        verifyBuiltArtifactsCommand2.options.mavenOptions.repositoryUrl = REPOSITORY_URL;
        var coords2 = "org.mock-server:mockserver-netty:jar:jar-with-dependencies:5.15.0";
        var gav2 = coordsToGav(coords2);
        assertThat(gav2).extracting("groupId", "artifactId", "extension", "classifier", "version")
                .containsExactly("org.mock-server", "mockserver-netty", "jar", "jar-with-dependencies", "5.15.0");
        var optPath2 = verifyBuiltArtifactsCommand2.resolveArtifact(coordsToPath(coords2));
        assertThat(optPath2).hasValueSatisfying(path -> assertThat(path).isRegularFile().hasFileName(
                gav2.getArtifactId() + "-" + gav2.getVersion() + "-" + gav2.getClassifier() + "." + gav2.getExtension()));
        var args = new String[] { "-of", optPath2.get().toAbsolutePath().toString(), "-nf",
                optPath.get().toAbsolutePath().toString() };
        var exitCode = new CommandLine(verifyBuiltArtifactsCommand2).execute(args);
        assertThat(exitCode).isOne();
    }
}

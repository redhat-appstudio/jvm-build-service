package com.redhat.hacbs.container.analyser.build;

import static com.redhat.hacbs.container.analyser.build.JavaVersion.JAVA_8;
import static com.redhat.hacbs.container.analyser.build.maven.MavenJavaVersionDiscovery.filterJavaVersions;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.junit.jupiter.api.Test;

class JavaVersionDiscoveryTest {
    @Test
    void testPropertyInterpolation()
            throws IOException, XmlPullParserException {
        Path pomFile = Paths.get("src/test/resources/pom.xml").toAbsolutePath();
        try (BufferedReader pomReader = Files.newBufferedReader(pomFile)) {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(pomReader);

            InvocationBuilder invocationBuilder = new InvocationBuilder(null, new HashMap<>(), "1");
            filterJavaVersions(pomFile, model, invocationBuilder);

            assertThat(invocationBuilder.minJavaVersion).isNotNull().isEqualTo(new JavaVersion("9"));
            assertThat(invocationBuilder.maxJavaVersion).isNotNull().isEqualTo(JAVA_8);
        }
    }

    @Test
    void testJavaVersionDiscovery() throws IOException, XmlPullParserException {
        var pomFile = Paths.get("src/test/resources/relaxng-datatype-java/pom.xml").toAbsolutePath();

        try (var pomReader = Files.newBufferedReader(pomFile)) {
            var reader = new MavenXpp3Reader();
            var model = reader.read(pomReader);
            var invocationBuilder = new InvocationBuilder(null, new HashMap<>(), "1");
            filterJavaVersions(pomFile, model, invocationBuilder);
            assertThat(invocationBuilder.minJavaVersion).isNull();
            assertThat(invocationBuilder.maxJavaVersion).isNotNull().isEqualTo(JAVA_8);
        }
    }

}

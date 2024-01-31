package com.redhat.hacbs.container.analyser.build;

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

import com.redhat.hacbs.container.analyser.build.maven.MavenJavaVersionDiscovery;

class JavaVersionDiscoveryTest {
    @Test
    public void checkPropertyInterpolation()
            throws IOException, XmlPullParserException {
        Path pomFile = Paths.get("src/test/resources/pom.xml").toAbsolutePath();
        try (BufferedReader pomReader = Files.newBufferedReader(pomFile)) {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(pomReader);

            InvocationBuilder invocationBuilder = new InvocationBuilder(null, new HashMap<>(), "1");
            MavenJavaVersionDiscovery.filterJavaVersions(pomFile, model, invocationBuilder);

            assertThat(invocationBuilder.minJavaVersion).isEqualTo(new JavaVersion("9"));
            assertThat(invocationBuilder.maxJavaVersion).isEqualTo(new JavaVersion("8"));
        }
    }
}

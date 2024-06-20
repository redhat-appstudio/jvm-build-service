package com.redhat.hacbs.domainproxy;

import static com.redhat.hacbs.domainproxy.ExternalProxyEndpoint.dependencies;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.common.sbom.GAV;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(DomainProxyHackTest.DomainProxyHackTestProfile.class)
class DomainProxyHackTest {

    @Inject
    DomainProxyHack domainProxyHack;

    public static final String SBOM_FILENAME = "sbom.json";

    @BeforeEach
    public void beforeEach() {
        dependencies.clear();
    }

    @AfterEach
    public void afterEach() {
        domainProxyHack.sbomOutputDir.resolve(SBOM_FILENAME).toFile().delete();
    }

    @AfterAll
    public static void afterAll() {
        dependencies.clear();
    }

    @Test
    public void testCreateBom() throws IOException {
        Dependency dependency = new Dependency(new GAV("org.apache.maven.plugins", "maven-jar-plugin", "3.4.1"), null);
        dependencies.add(dependency);
        dependencies.add(new Dependency(new GAV("io.netty", "netty-transport-native-epoll", "4.1.111.Final"),
                "linux-aarch_64"));
        domainProxyHack.createBom();
        String expectedSbomContents = """
                {
                  "bomFormat" : "CycloneDX",
                  "specVersion" : "1.6",
                  "version" : 1,
                  "components" : [
                    {
                      "type" : "library",
                      "group" : "io.netty",
                      "name" : "netty-transport-native-epoll",
                      "version" : "4.1.111.Final",
                      "purl" : "pkg:maven/io.netty/netty-transport-native-epoll@4.1.111.Final?classifier=linux-aarch_64",
                      "properties" : [
                        {
                          "name" : "package:type",
                          "value" : "maven"
                        },
                        {
                          "name" : "package:language",
                          "value" : "java"
                        }
                      ]
                    },
                    {
                      "type" : "library",
                      "group" : "org.apache.maven.plugins",
                      "name" : "maven-jar-plugin",
                      "version" : "3.4.1",
                      "purl" : "pkg:maven/org.apache.maven.plugins/maven-jar-plugin@3.4.1",
                      "properties" : [
                        {
                          "name" : "package:type",
                          "value" : "maven"
                        },
                        {
                          "name" : "package:language",
                          "value" : "java"
                        }
                      ]
                    }
                  ]
                }""".replaceAll("\n", System.lineSeparator());
        String sbomContents = Files.readString(domainProxyHack.sbomOutputDir.resolve(SBOM_FILENAME));
        assertEquals(expectedSbomContents, sbomContents);
    }

    @Test
    public void testMissingSbom() throws IOException {
        domainProxyHack.createBom();
        Assertions.assertThrows(NoSuchFileException.class,
                () -> Files.readString(domainProxyHack.sbomOutputDir.resolve(SBOM_FILENAME)));
    }

    public static class DomainProxyHackTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                return Map.of("sbom-output-directory", Files.createTempDirectory("output").toAbsolutePath().toString());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}

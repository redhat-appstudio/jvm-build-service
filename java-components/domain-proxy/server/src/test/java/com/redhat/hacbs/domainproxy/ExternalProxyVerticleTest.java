package com.redhat.hacbs.domainproxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.redhat.hacbs.domainproxy.DomainProxyServer.LOCALHOST;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.inject.Inject;

import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;

@QuarkusTest
@TestProfile(ExternalProxyVerticleTestProfile.class)
class ExternalProxyVerticleTest {

    static final String MD5_HASH = "ea3ca57f8f99d1d210d1b438c9841440";

    private static WireMockServer wireMockServer;

    @Inject
    @ConfigProperty(name = "server-http-port")
    int httpServerPort;

    @BeforeAll
    public static void before() throws IOException {
        wireMockServer = new WireMockServer(wireMockConfig().port(2002).httpsPort(2003));
        wireMockServer.start();
        wireMockServer.stubFor(
                get(urlEqualTo("/com/foo/bar/1.0/bar-1.0.pom"))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "text/xml")
                                .withBody(
                                        Files.readString(Path.of("src/test/resources/bar-1.0.pom"), StandardCharsets.UTF_8))));
    }

    @AfterAll
    public static void after() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private RequestSpecification httpRequest() {
        return given().proxy(LOCALHOST, httpServerPort).port(wireMockServer.port());
    }

    private RequestSpecification httpsRequest() {
        return given().proxy(LOCALHOST, httpServerPort).port(wireMockServer.httpsPort()).relaxedHTTPSValidation();
    }

    @Test
    public void testDownloadDependencyHTTP() {
        final byte[] jar = httpRequest().get("http://" + LOCALHOST + "/com/foo/bar/1.0/bar-1.0.pom")
                .asByteArray();
        assertEquals(MD5_HASH, DigestUtils.md5Hex(jar));
    }

    @Test
    public void testDownloadDependencyHTTPS() {
        final byte[] jar = httpsRequest().get("https://" + LOCALHOST + "/com/foo/bar/1.0/bar-1.0.pom")
                .asByteArray();
        assertEquals(MD5_HASH, DigestUtils.md5Hex(jar));
    }

    @Test
    public void testMissingDependencyHTTP() {
        httpRequest().get("http://" + LOCALHOST + "/com/foo/bar/2.0/bar-2.0.pom")
                .then()
                .statusCode(HttpResponseStatus.NOT_FOUND.code());
    }

    @Test
    public void testMissingDependencyHTTPS() {
        httpsRequest().get("https://" + LOCALHOST + "/com/foo/bar/2.0/bar-2.0.pom")
                .then()
                .statusCode(HttpResponseStatus.NOT_FOUND.code());
    }

    @Test
    public void testNotWhitelistedHTTP() {
        httpRequest().get(
                "http://repo1.maven.org/maven2/org/apache/maven/plugins/maven-jar-plugin/3.4.1/maven-jar-plugin-3.4.1.jar")
                .then()
                .statusCode(HttpResponseStatus.NOT_FOUND.code());
    }

    @Test
    public void testNotWhitelistedHTTPS() {
        httpsRequest().get(
                "https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-jar-plugin/3.4.1/maven-jar-plugin-3.4.1.jar")
                .then()
                .statusCode(HttpResponseStatus.NOT_FOUND.code());
    }
}

package com.redhat.hacbs.domainproxy;

import static com.redhat.hacbs.domainproxy.ExternalProxyEndpoint.dependencies;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.common.sbom.GAV;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestHTTPEndpoint(ExternalProxyEndpoint.class)
class ExternalProxyEndpointTest {

    @BeforeEach
    public void beforeEach() {
        dependencies.clear();
    }

    @AfterAll
    public static void afterAll() {
        dependencies.clear();
    }

    @Test
    public void testDownloadDependency() {
        byte[] jar = given()
                .when()
                .get("main/org/apache/maven/plugins/maven-jar-plugin/3.4.1/maven-jar-plugin-3.4.1.jar").asByteArray();
        assertEquals("27934a80a9fe932f50887f4c79cb539d", DigestUtils.md5Hex(jar));
        Dependency dependency = new Dependency(new GAV("org.apache.maven.plugins", "maven-jar-plugin", "3.4.1"), null);
        assertEquals(1, dependencies.size());
        assertThat(dependencies, hasItems(dependency));
    }

    @Test
    public void testDownloadDependencyWithClassifier() {
        byte[] jar = given()
                .when()
                .get("main/io/netty/netty-transport-native-epoll/4.1.111.Final/netty-transport-native-epoll-4.1.111.Final-linux-aarch_64.jar")
                .asByteArray();
        assertEquals("ddacd8fbbc4e883e17c9b735cd9fc7b0", DigestUtils.md5Hex(jar));
        Dependency dependency = new Dependency(new GAV("io.netty", "netty-transport-native-epoll", "4.1.111.Final"),
                "linux-aarch_64");
        assertEquals(1, dependencies.size());
        assertThat(dependencies, hasItems(dependency));
    }

    @Test
    public void testMissingDependency() {
        given()
                .when()
                .get("main/org/apache/maven/plugins/maven-jar-plugin/3.4.1/maven-jar-plugin-3.4.1.jar1")
                .then()
                .statusCode(404);
    }

    @Test
    public void testInvalidRoot() {
        given()
                .when()
                .get("foo/org/apache/maven/plugins/maven-jar-plugin/3.4.1/maven-jar-plugin-3.4.1.jar")
                .then()
                .statusCode(404);
    }
}

package com.redhat.hacbs.domainproxy;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestHTTPEndpoint(ExternalProxyEndpoint.class)
class ExternalProxyEndpointTest {

    @Test
    public void testDownloadDependency() {
        byte[] jar = given()
                .when()
                .get("main/org/apache/maven/plugins/maven-jar-plugin/3.4.1/maven-jar-plugin-3.4.1.jar").asByteArray();
        assertEquals("27934a80a9fe932f50887f4c79cb539d", DigestUtils.md5Hex(jar));
    }

    @Test
    public void testDownloadDependencyWithClassifier() {
        byte[] jar = given()
                .when()
                .get("main/io/netty/netty-transport-native-epoll/4.1.111.Final/netty-transport-native-epoll-4.1.111.Final-linux-aarch_64.jar")
                .asByteArray();
        assertEquals("ddacd8fbbc4e883e17c9b735cd9fc7b0", DigestUtils.md5Hex(jar));
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

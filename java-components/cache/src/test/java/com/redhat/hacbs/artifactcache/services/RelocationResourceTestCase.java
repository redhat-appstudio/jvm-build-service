package com.redhat.hacbs.artifactcache.services;

import static org.hamcrest.CoreMatchers.equalTo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.redhat.hacbs.artifactcache.artifactwatch.RebuiltArtifacts;
import com.redhat.hacbs.artifactcache.resources.CacheMavenResource;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.restassured.RestAssured;
import io.restassured.parsing.Parser;

@QuarkusTest
@TestHTTPEndpoint(CacheMavenResource.class)
public class RelocationResourceTestCase {

    long time = System.currentTimeMillis();

    @InjectMock
    RebuiltArtifacts rebuiltArtifacts;

    @BeforeEach
    public void setup() {
        Mockito.when(rebuiltArtifacts.isPossiblyRebuilt(ArgumentMatchers.any(String.class))).thenReturn(true);
    }

    @Test
    public void testRelocationExactPom() {
        RestAssured.registerParser("application/octet-stream", Parser.XML);
        RestAssured.given()
                .when().get("default/" + time + "/io/quarkus/gizmo/gizmo/1.0.9.Final/gizmo-1.0.9.pom")
                .then()
                .statusCode(200)
                .and()
                .assertThat().body("project.groupId", equalTo("io.quarkus.gizmo")).and()
                .assertThat().body("project.artifactId", equalTo("gizmo")).and()
                .assertThat().body("project.version", equalTo("1.0.9.Final")).and()
                .assertThat().body("project.distributionManagement.relocation.groupId", equalTo("io.quarkus.gizmo")).and()
                .assertThat().body("project.distributionManagement.relocation.artifactId", equalTo("gizmo")).and()
                .assertThat().body("project.distributionManagement.relocation.version", equalTo("1.0.9.Final-redhat-00001"));
    }

    @Test
    public void testRelocationExactPomSha1() {
        RestAssured.given()
                .when().get("default/" + time + "/io/quarkus/gizmo/gizmo/1.0.9.Final/gizmo-1.0.9.pom.sha1")
                .then()
                .statusCode(200)
                .and()
                .assertThat().body(equalTo("4d9ca604033d11c0f7cf7fadd89837b0ff67e4b6"));
    }

    @Test
    public void testRelocationExactJar() {
        RestAssured.given()
                .when().get("default/" + time + "/io/quarkus/gizmo/gizmo/1.0.9.Final/gizmo-1.0.9.jar")
                .then()
                .statusCode(404);
    }

    @Test
    public void testRelocationWithRegexPom() {
        RestAssured.registerParser("application/octet-stream", Parser.XML);
        RestAssured.given()
                .when().get("default/" + time + "/io/quarkus/http/quarkus-http-core/4.1.7/quarkus-http-core-4.1.7.pom")
                .then()
                .statusCode(200)
                .and()
                .assertThat().body("project.groupId", equalTo("io.quarkus.http")).and()
                .assertThat().body("project.artifactId", equalTo("quarkus-http-core")).and()
                .assertThat().body("project.version", equalTo("4.1.7")).and()
                .assertThat().body("project.distributionManagement.relocation.groupId", equalTo("io.quarkus.http")).and()
                .assertThat().body("project.distributionManagement.relocation.artifactId", equalTo("quarkus-http-core")).and()
                .assertThat().body("project.distributionManagement.relocation.version", equalTo("4.1.7-redhat-00002"));

        RestAssured.given()
                .when().get("default/" + time + "/io/quarkus/http/quarkus-http-servlet/4.1.7/quarkus-http-servlet-4.1.7.pom")
                .then()
                .statusCode(200)
                .and()
                .assertThat().body("project.groupId", equalTo("io.quarkus.http")).and()
                .assertThat().body("project.artifactId", equalTo("quarkus-http-servlet")).and()
                .assertThat().body("project.version", equalTo("4.1.7")).and()
                .assertThat().body("project.distributionManagement.relocation.groupId", equalTo("io.quarkus.http")).and()
                .assertThat().body("project.distributionManagement.relocation.artifactId", equalTo("quarkus-http-servlet"))
                .and()
                .assertThat().body("project.distributionManagement.relocation.version", equalTo("4.1.7-redhat-00002"));

        RestAssured.given()
                .when().get("default/" + time + "/io/quarkus/arc/arc/2.7.6.Final/arc-2.7.6.pom")
                .then()
                .statusCode(200)
                .and()
                .assertThat().body("project.groupId", equalTo("io.quarkus.arc")).and()
                .assertThat().body("project.artifactId", equalTo("arc")).and()
                .assertThat().body("project.version", equalTo("2.7.6.Final")).and()
                .assertThat().body("project.distributionManagement.relocation.groupId", equalTo("io.quarkus.arc")).and()
                .assertThat().body("project.distributionManagement.relocation.artifactId", equalTo("arc"))
                .and()
                .assertThat().body("project.distributionManagement.relocation.version", equalTo("2.7.6.Final-redhat-00006"));
    }

    @Test
    public void testRelocationWithRegexPomSha1() {
        RestAssured.given()
                .when().get("default/" + time + "/io/quarkus/http/quarkus-http-core/4.1.7/quarkus-http-core-4.1.7.pom.sha1")
                .then()
                .statusCode(200)
                .and()
                .assertThat().body(equalTo("632f5ae02f4bb7096cd5eaf6e83aecc5d46757c5"));

        RestAssured.given()
                .when()
                .get("default/" + time + "/io/quarkus/http/quarkus-http-servlet/4.1.7/quarkus-http-servlet-4.1.7.pom.sha1")
                .then()
                .statusCode(200)
                .and()
                .assertThat().body(equalTo("e2f4031fbebdeda43cd88fda65a8703e8b6e3a50"));

        RestAssured.given()
                .when().get("default/" + time + "/io/quarkus/arc/arc/2.7.6.Final/arc-2.7.6.pom.sha1")
                .then()
                .statusCode(200)
                .and()
                .assertThat().body(equalTo("7507ebadef9ee89ba73b0127b8d2966606290cf4"));

    }
}

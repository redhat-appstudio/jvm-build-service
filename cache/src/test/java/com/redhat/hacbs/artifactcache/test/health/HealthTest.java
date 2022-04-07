package com.redhat.hacbs.artifactcache.test.health;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
public class HealthTest {
    @Test
    public void testMainHealthCheck() {

        RestAssured.given()
                .when().get("/q/health")
                .then()
                .statusCode(200)
                .body("status", CoreMatchers.equalTo("UP"));
    }
}

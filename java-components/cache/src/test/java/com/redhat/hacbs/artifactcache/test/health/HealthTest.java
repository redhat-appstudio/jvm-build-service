package com.redhat.hacbs.artifactcache.test.health;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
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
                .body("status", CoreMatchers.equalTo("UP"))
                .body("checks.name", Matchers.hasItems("Basic health check"))
                .rootPath("checks.find { it.name == '%s' }")
                // Basic health check
                .body("status", RestAssured.withArgs("Basic health check"), CoreMatchers.equalTo("UP"))
                .body("data.uptime", RestAssured.withArgs("Basic health check"), CoreMatchers.isA(Integer.class))
                .body("data.startTime", RestAssured.withArgs("Basic health check"), CoreMatchers.isA(Long.class));
    }
}

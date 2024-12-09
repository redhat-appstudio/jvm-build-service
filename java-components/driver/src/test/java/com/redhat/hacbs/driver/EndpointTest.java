package com.redhat.hacbs.driver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.api.dto.ComponentVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.driver.clients.IndyService;
import com.redhat.hacbs.driver.clients.IndyTokenRequestDTO;
import com.redhat.hacbs.driver.clients.IndyTokenResponseDTO;
import com.redhat.hacbs.driver.dto.BuildRequest;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

@WithKubernetesTestServer
@QuarkusTest
public class EndpointTest {

    @KubernetesTestServer
    KubernetesServer mockServer;

    @Inject
    KubernetesClient client;

    @InjectMock
    @RestClient
    IndyService indyService;

    @BeforeEach
    public void setup() {
        when(indyService.getAuthToken(any(IndyTokenRequestDTO.class), any(String.class)))
                .thenReturn(new IndyTokenResponseDTO("token-for-builder-pod"));
    }

    @Test
    void verify() {

        BuildRequest request = BuildRequest.builder().namespace("default").podMemoryOverride("1Gi").build();
        RestAssured.given().contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/build")
                .then()
                .statusCode(200);
    }

    @Test
    void version() {
        var result = RestAssured.given()
                .when()
                .get("/version")
                .as(ComponentVersion.class);
        Assertions.assertEquals("konflux-build-driver", result.getName());
    }
}

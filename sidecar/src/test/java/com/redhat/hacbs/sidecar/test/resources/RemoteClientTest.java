package com.redhat.hacbs.sidecar.test.resources;

import static org.hamcrest.CoreMatchers.is;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.zip.ZipOutputStream;

import javax.ws.rs.WebApplicationException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;
import com.redhat.hacbs.classfile.tracker.TrackingData;
import com.redhat.hacbs.sidecar.resources.MavenProxyResource;
import com.redhat.hacbs.sidecar.services.RemoteClient;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.restassured.RestAssured;

@QuarkusTest
@TestHTTPEndpoint(MavenProxyResource.class)
public class RemoteClientTest {

    @InjectMock
    RemoteClient remoteClient;

    @Test
    public void testFetchingAJar() {

        Mockito.when(remoteClient.get("default", "io/quarkus", "quarkus-jaxb",
                "2.7.5.Final",
                "quarkus-jaxb-2.7.5.Final.jar")).thenReturn("ajar".getBytes());

        RestAssured.given()
                .when().get("/io/quarkus/quarkus-jaxb/2.7.5.Final/quarkus-jaxb-2.7.5.Final.jar")
                .then()
                .statusCode(200)
                .body(is("ajar"));
    }

    @Test
    public void testFetchingNonExistingJar() {

        Mockito.when(remoteClient.get("default", "io/quarkus", "quarkus-jaxb",
                "2.7.5555555.Final",
                "quarkus-jaxb-2.7.5555555.Final.jar")).thenThrow(new WebApplicationException("Not there", 404));

        RestAssured.given()
                .when().get("/io/quarkus/quarkus-jaxb/2.7.5555555.Final/quarkus-jaxb-2.7.5555555.Final.jar")
                .then()
                .statusCode(404);
    }

    @Test
    public void testProvenanceTracking() throws Exception {

        byte[] thisClass = getClass().getResourceAsStream(getClass().getSimpleName() + ".class").readAllBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.putNextEntry(new JarEntry(getClass().getPackageName().replace(".", "/") + getClass().getSimpleName() + ".class"));
        zip.write(thisClass);
        zip.close();

        Mockito.when(remoteClient.get("default", "io/quarkus", "quarkus-core",
                "2.7.5.Final",
                "quarkus-core-2.7.5.Final.jar")).thenReturn(out.toByteArray());

        byte[] result = RestAssured.given()
                .when().get("/io/quarkus/quarkus-core/2.7.5.Final/quarkus-core-2.7.5.Final.jar")
                .then()
                .statusCode(200)
                .extract().body().asByteArray();

        Assertions.assertEquals(Collections.singleton(new TrackingData("io.quarkus:quarkus-core:2.7.5.Final", null)),
                ClassFileTracker.readTrackingDataFromJar(result));
    }
}

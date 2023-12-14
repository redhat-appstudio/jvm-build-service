package com.redhat.hacbs.container.results;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.rest.client.RestClientBuilder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;

@ApplicationScoped
public class ResultsUpdater {

    public static ObjectMapper MAPPER = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public void updateResults(String taskRunName, Map<String, String> results) {

        Path ca = Paths.get("/workspace/tls/service-ca.crt");

        var cache = System.getenv("JVM_BUILD_WORKSPACE_ARTIFACT_CACHE_PORT_80_TCP_ADDR");
        var cacheTls = System.getenv("JVM_BUILD_WORKSPACE_ARTIFACT_CACHE_TLS_PORT_443_TCP_ADDR");
        if (cacheTls != null && Files.exists(ca)) {
            try {
                //read the current namespace
                var namespace = Files.readString(Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/namespace"));
                //we need to use the host name for TLS
                RestClientBuilder.newBuilder()
                        .baseUri(new URI("https://jvm-build-workspace-artifact-cache-tls." + namespace + ".svc.cluster.local"))
                        .build(ResultsUpdaterRestClient.class).updateResults(taskRunName, results);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else if (cache != null) {
            try {
                RestClientBuilder.newBuilder().baseUri(new URI("http://" + cache))
                        .build(ResultsUpdaterRestClient.class).updateResults(taskRunName, results);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        } else {
            for (var e : results.entrySet()) {
                try {
                    Files.writeString(Paths.get("/tekton/results", e.getKey()), e.getValue());
                } catch (IOException ex) {
                    Log.errorf(ex, "Failed to write result %s", e.getKey());
                }
            }
        }

    }

}

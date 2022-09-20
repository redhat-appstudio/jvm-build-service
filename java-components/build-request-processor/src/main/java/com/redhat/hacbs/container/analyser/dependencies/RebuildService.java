package com.redhat.hacbs.container.analyser.dependencies;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import javax.inject.Singleton;

import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;

import io.quarkus.logging.Log;

@Singleton
public class RebuildService {

    public void rebuild(String cacheUrl, Set<String> gavs) {
        Log.infof("Identified %s Community Dependencies: %s", gavs.size(), new TreeSet<>(gavs));
        try (var client = HttpClientBuilder.create().build()) {
            var put = new HttpPut(cacheUrl);
            put.setEntity(new StringEntity(String.join(",", gavs)));
            try (var result = client.execute(put)) {
                if (result.getStatusLine().getStatusCode() > 204) {
                    throw new RuntimeException("Unexpected response from server: " + result.getStatusLine().getStatusCode());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

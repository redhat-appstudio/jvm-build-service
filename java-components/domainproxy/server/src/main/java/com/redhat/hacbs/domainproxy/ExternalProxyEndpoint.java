package com.redhat.hacbs.domainproxy;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;
import io.vertx.core.Vertx;

@Path("/")
public class ExternalProxyEndpoint {

    final Client client;
    final Map<String, String> proxyTargets;
    static final Set<Dependency> dependencies = ConcurrentHashMap.newKeySet();
    static final String JAR_EXTENSION = ".jar";

    public ExternalProxyEndpoint(Config config,
            @ConfigProperty(name = "proxy-paths") List<String> endpoints, Vertx vertx) {
        client = ClientBuilder.newBuilder()
                .build();
        Map<String, String> targets = new HashMap<>();
        for (var endpoint : endpoints) {
            var proxyTarget = config.getConfigValue("proxy-path." + endpoint + ".target").getValue();
            targets.put(endpoint, proxyTarget);
        }
        this.proxyTargets = targets;
    }

    @GET
    @Path("{root}/{path:.*}")
    public InputStream get(@PathParam("root") String root, @PathParam("path") String path) {
        var target = proxyTargets.get(root);
        if (target == null) {
            throw new NotFoundException();
        }
        var response = client.target(target + "/" + path).request().get();
        if (response.getStatus() != 200) {
            Log.errorf("Response %d %s", response.getStatus(), response.readEntity(String.class));
            throw new NotFoundException();
        }

        if (path.endsWith(JAR_EXTENSION)) {
            String[] pathComponents = path.split("/");
            String artifact = pathComponents[pathComponents.length - 3];
            String version = pathComponents[pathComponents.length - 2];
            String group = "";
            String classifier = null;
            String potentialClassifier = pathComponents[pathComponents.length - 1];
            for (int i = 0; i < pathComponents.length - 3; i++) {
                group += pathComponents[i];
                if (i < pathComponents.length - 4) {
                    group += ".";
                }
            }
            String artifactAndVersionPrefix = artifact + "-" + version + "-";
            // Has classifier due to presence of '-' after version
            if (potentialClassifier.startsWith(artifactAndVersionPrefix)) {
                classifier = potentialClassifier.substring(artifactAndVersionPrefix.length(),
                        potentialClassifier.length() - JAR_EXTENSION.length());
            }
            dependencies.add(new Dependency(new GAV(group, artifact, version), classifier));
        }

        return response.readEntity(InputStream.class);
    }
}

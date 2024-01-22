package com.redhat.hacbs.management.resources;

import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.common.tools.completer.BuildCompleter;
import com.redhat.hacbs.common.tools.logging.LogExtractor;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteSpec;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRun;
import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;

public class BuildLogs {

    protected enum Type {
        DISCOVERY,
        BUILD
    }

    private static final String DEV_PATH = "/apis/results.tekton.dev";

    private static final String PROD_PATH = "/api/k8s/plugins/tekton-results/workspaces/";

    @Inject
    S3Client s3Client;

    @ConfigProperty(name = "jbs.s3.sync.enabled", defaultValue = "true")
    boolean s3;

    protected Response extractLog(Type logType, URI uri, String id) {
        if (s3) {
            try {
                InputStream stream = s3Client.getObject(b -> {
                    String path = uri.getPath().substring(1);
                    String bucket = uri.getHost();
                    Log.infof("requesting logs %s from bucket %s", path, bucket);
                    b.bucket(bucket).key(path);
                });

                return Response.ok(stream, MediaType.TEXT_PLAIN_TYPE).build();
            } catch (SdkClientException e) {
                Log.error("Unable to retrieve logs via AWS S3 ; falling back to Tekton-Results retrieval", e);

                return alternateLogging(logType, id);
            }
        } else {
            return alternateLogging(logType, id);
        }
    }

    protected Response alternateLogging(Type logType, String id) {
        var client = Arc.container().instance(OpenShiftClient.class).get();
        DependencyBuild theBuild;
        Map<String, DependencyBuild> names = BuildCompleter.createNames();
        theBuild = names.get(id);
        if (theBuild == null) {
            for (var n : names.values()) {
                if (id.equals(n.getMetadata().getName())) {
                    //can also specify by kube name
                    theBuild = n;
                    break;
                }
            }
        }
        LinkedHashSet<Integer> buildNumbers = new LinkedHashSet<>();
        for (int i = 0;; i++) {
            var pr = client.resources(PipelineRun.class).withName(theBuild.getMetadata().getName() + "-build-" + i);
            if (pr == null || pr.get() == null) {
                break;
            }
            buildNumbers.add(i);
        }
        if (theBuild.getStatus().getBuildAttempts() != null) {
            for (int i = 0; i < theBuild.getStatus().getBuildAttempts().size(); ++i) {
                buildNumbers.add(i);
            }
        }
        Log.warnf("Selected build %s with build numbers %s", theBuild.getMetadata().getName(), buildNumbers);
        StringBuilder result = new StringBuilder();
        String host;
        String restPath;
        try {
            Route route = client.routes().inNamespace("openshift-pipelines").withName("tekton-results").get();
            if (route == null) {
                Log.warnf(
                        "No Tekton-Results found in namespace openshift-pipelines ; falling back to legacy retrieval");
                switch (logType) {
                    case BUILD -> result.append(LogExtractor.legacyBuildLogRetrieval(client, buildNumbers, theBuild));
                    case DISCOVERY -> result.append(LogExtractor.legacyDiscoveryLogRetrieval(client, theBuild));
                }
                return Response.ok(result.toString(), MediaType.TEXT_PLAIN_TYPE).build();
            }
            RouteSpec routeSpec = route.getSpec();
            host = routeSpec.getHost();
            restPath = DEV_PATH;
        } catch (KubernetesClientException ignore) {

            String namespace = client.getNamespace();
            if (namespace.endsWith("-tenant")) {
                namespace = namespace.substring(0, namespace.length() - "-tenant".length());
            }
            restPath = PROD_PATH + namespace + DEV_PATH;
            host = "console.redhat.com";
        }
        Log.warnf("REST path: " + host + ":443" + restPath);
        switch (logType) {
            case BUILD ->
                result.append(LogExtractor.buildLogRetrieval(client, host, 443, restPath, buildNumbers, theBuild));
            case DISCOVERY -> result.append(LogExtractor.discoveryLogRetrieval(client, host, 443, restPath, theBuild));
        }
        return Response.ok(result.toString(), MediaType.TEXT_PLAIN_TYPE).build();
    }
}

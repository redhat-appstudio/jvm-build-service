package com.redhat.hacbs.management.resources;

import java.util.LinkedHashSet;
import java.util.Map;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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

public class BuildLogs {

    protected enum Type {
        DEPLOY,
        DISCOVERY,
        BUILD
    }

    private static final String DEV_PATH = "/apis/results.tekton.dev";

    private static final String PROD_PATH = "/api/k8s/plugins/tekton-results/workspaces/";

    protected Response extractLog(Type logType, String id) {
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
                    case DEPLOY -> result.append(LogExtractor.legacyDeployLogRetrieval(client, theBuild));
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
            case DEPLOY -> result.append(LogExtractor.deployLogRetrieval(client, host, 443, restPath, theBuild));
        }
        return Response.ok(result.toString(), MediaType.TEXT_PLAIN_TYPE).build();
    }
}

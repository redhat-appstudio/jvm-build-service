package io.github.redhatappstudio.jvmbuild.cli.builds;

import java.util.LinkedHashSet;
import java.util.Map;

import com.redhat.hacbs.common.tools.completer.BuildCompleter;
import com.redhat.hacbs.common.tools.logging.LogExtractor;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteSpec;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRun;
import io.github.redhatappstudio.jvmbuild.cli.artifacts.ArtifactBuildCompleter;
import io.github.redhatappstudio.jvmbuild.cli.artifacts.GavCompleter;
import io.github.redhatappstudio.jvmbuild.cli.util.BuildConverter;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "logs", mixinStandardHelpOptions = true, description = "Displays the logs for the build")
public class BuildLogsCommand implements Runnable {

    private static final String DEV_PATH = "/apis/results.tekton.dev";

    private static final String PROD_PATH = "/api/k8s/plugins/tekton-results/workspaces/";

    @CommandLine.Option(names = "-g", description = "The build to view, specified by GAV", completionCandidates = GavCompleter.class)
    String gav;

    @CommandLine.Option(names = "-a", description = "The build to view, specified by ArtifactBuild name", completionCandidates = ArtifactBuildCompleter.class)
    String artifact;

    @CommandLine.Option(names = "-b", description = "The build to view, specified by build id", completionCandidates = BuildCompleter.class)
    String build;

    @CommandLine.Option(names = "-n", description = "The build number", defaultValue = "-1")
    int buildNo;

    @CommandLine.Option(names = "-l", description = "Use legacy retrieval")
    boolean legacyRetrieval = false;

    @CommandLine.Option(names = "-u", description = "URL for Tekton-Results")
    String tektonUrl = "console.redhat.com";

    // Normally this will always be 443 but this allows the test to override and setup a wiremock on another port.
    int defaultPort = 443;

    @Override
    public void run() {
        var client = Arc.container().instance(OpenShiftClient.class).get();
        DependencyBuild theBuild;
        if (build != null) {
            if (artifact != null || gav != null) {
                throwUnspecified();
            }
            Map<String, DependencyBuild> names = BuildCompleter.createNames();
            theBuild = names.get(build);
            if (theBuild == null) {
                for (var n : names.values()) {
                    if (build.equals(n.getMetadata().getName())) {
                        //can also specify by kube name
                        theBuild = n;
                        break;
                    }
                }
            }
        } else if (artifact != null) {
            if (gav != null) {
                throwUnspecified();
            }
            ArtifactBuild ab = ArtifactBuildCompleter.createNames().get(artifact);
            theBuild = BuildConverter.buildToArtifact(client, ab);
        } else if (gav != null) {
            ArtifactBuild ab = GavCompleter.createNames().get(gav);
            theBuild = BuildConverter.buildToArtifact(client, ab);
        } else {
            throw new RuntimeException("Must specify one of -b, -a or -g");
        }
        if (theBuild == null) {
            throw new RuntimeException("Build not found");
        }

        LinkedHashSet<Integer> buildNumbers = new LinkedHashSet<>();
        if (buildNo >= 0) {
            buildNumbers.add(buildNo);
        } else {
            //all builds we have runs for
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
        }
        System.out.println("Selected build: " + theBuild.getMetadata().getName());

        if (legacyRetrieval) {
            System.out.println(LogExtractor.legacyDiscoveryLogRetrieval(client, theBuild));
            System.out.println(LogExtractor.legacyBuildLogRetrieval(client, buildNumbers, theBuild));
        } else {
            String host;
            String restPath;
            try {
                Route route = client.routes().inNamespace("openshift-pipelines").withName("tekton-results").get();
                if (route == null) {
                    System.err.println(
                            "No Tekton-Results found in namespace openshift-pipelines ; falling back to legacy retrieval");
                    System.out.println(LogExtractor.legacyDiscoveryLogRetrieval(client, theBuild));
                    System.out.println(LogExtractor.legacyBuildLogRetrieval(client, buildNumbers, theBuild));
                    return;
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
                host = tektonUrl;
            }
            System.out.println("REST path: " + host + ":" + defaultPort + restPath);

            System.out.println(LogExtractor.discoveryLogRetrieval(client, host, defaultPort, restPath, theBuild));
            System.out.println(LogExtractor.buildLogRetrieval(client, host, defaultPort, restPath, buildNumbers,
                    theBuild));
        }
    }

    private void throwUnspecified() {
        throw new RuntimeException("Can only specify one of -b, -a or -g");
    }
}

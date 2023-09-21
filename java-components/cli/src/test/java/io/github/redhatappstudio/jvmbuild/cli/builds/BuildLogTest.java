package io.github.redhatappstudio.jvmbuild.cli.builds;

import static com.github.stefanbirkner.systemlambda.SystemLambda.tapSystemOut;
import static io.github.redhatappstudio.jvmbuild.cli.WireMockExtensions.LOG_UID;
import static io.github.redhatappstudio.jvmbuild.cli.WireMockExtensions.RESULT_UID;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildstatus.BuildAttempts;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildstatus.buildattempts.Build;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildstatus.buildattempts.build.Results;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildstatus.buildattempts.build.results.PipelineResults;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRun;
import io.fabric8.tekton.pipeline.v1beta1.TaskRun;
import io.github.redhatappstudio.jvmbuild.cli.TestComponentManager;
import io.github.redhatappstudio.jvmbuild.cli.WireMockExtensions;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(WireMockExtensions.class)
public class BuildLogTest extends TestComponentManager {

    private WireMockServer wireMockServer;

    @Test
    public void testPodLog()
            throws Exception {

        KubernetesClient kubernetesClient = server.getClient();

        String gav = "commons-net:commons-net:3.6";
        String abName = "commons.net.3.6-65df3c98";
        String podName = "step-create-pre-build-image";
        String pod1Log = "This is my pod log!";

        ArtifactBuild ab = kubernetesClient.resource(createArtifactBuild(
                abName,
                gav, ModelConstants.ARTIFACT_BUILD_COMPLETE)).create();

        DependencyBuild db = createDependencyBuild(
                ab,
                "b65da343c6ff99b4d15da62b349d9abb",
                abName,
                "3.6",
                "2c9b32abf0c0f5041d328a6034e30650b493eb4d",
                "https://github.com/apache/commons-net.git",
                "NET_3_6",
                ModelConstants.DEPENDENCY_BUILD_COMPLETE);
        BuildAttempts buildAttempts = new BuildAttempts();
        Build build = new Build();
        Results results = new Results();
        PipelineResults pipelineResults = new PipelineResults();
        db.getStatus().setBuildAttempts(Collections.singletonList(buildAttempts));
        buildAttempts.setBuild(build);
        build.setResults(results);
        results.setPipelineResults(pipelineResults);
        // This is what the REST call ends up looking up.
        pipelineResults.setLogs(
                "test-jvm-namespace/foo/" + RESULT_UID + "/foo/" + LOG_UID);

        PipelineRun pr = createPipelineRun(db, "-build-0");
        TaskRun tr = createTaskRun(pr, "-pre-build");
        Pod pod = createNewPod(tr, podName, Collections.emptyMap());

        kubernetesClient.resource(db).create();
        kubernetesClient.resource(pr).create();
        kubernetesClient.resource(tr).create();
        kubernetesClient.pods().resource(pod).create();

        // While getLog just uses pretty=false, watchLogs in PodOperationsImpl uses a slightly different URL
        // Most docs specify expect().withPath but Fabric8 team advised to use expect().get().withPath to get
        // this to work.
        server.expect().get().withPath("/api/v1/namespaces/test/pods/" +
                pod.getMetadata().getName() +
                "/log?pretty=false&container=" +
                podName +
                "&follow=true")
                .andReturn(200, pod1Log)
                .once();

        // Legacy
        BuildLogsCommand blc = new BuildLogsCommand();
        blc.gav = gav;
        blc.legacyRetrieval = true;
        String out = tapSystemOut(blc::run);
        assertTrue(out.contains("Logs for container"));
        assertTrue(out.contains(pod1Log));

        // Tekton-Results
        kubernetesClient.resource(createRoute()).inNamespace("openshift-pipelines").create();
        blc.legacyRetrieval = false;
        blc.defaultPort = wireMockServer.httpsPort();
        out = tapSystemOut(blc::run);
        assertTrue(out.contains(
                "Log information: [test-jvm-namespace, foo, 600f6ecc-342e-4793-94ce-6ac79d3891b0, foo, 3a0c8e9b-8ad6-4479-91ff-6bfcf4bb1c33]"));
        assertTrue(out.contains(pod1Log));
    }
}

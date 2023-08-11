package io.github.redhatappstudio.jvmbuild.cli.builds;

import static com.github.stefanbirkner.systemlambda.SystemLambda.tapSystemOut;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRun;
import io.fabric8.tekton.pipeline.v1beta1.TaskRun;
import io.github.redhatappstudio.jvmbuild.cli.TestComponentManager;
import io.github.redhatappstudio.jvmbuild.cli.TestResourceManager;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = TestResourceManager.class, restrictToAnnotatedClass = true)
public class BuildLogTest extends TestComponentManager {

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

        BuildLogsCommand blc = new BuildLogsCommand();
        blc.gav = gav;

        String out = tapSystemOut(blc::run);
        assertTrue(out.contains("Logs for container"));
        assertTrue(out.contains(pod1Log));
    }
}

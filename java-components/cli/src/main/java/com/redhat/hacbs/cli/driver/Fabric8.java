package com.redhat.hacbs.cli.driver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.tekton.client.TektonClient;
import io.fabric8.tekton.pipeline.v1.Param;
import io.fabric8.tekton.pipeline.v1.ParamBuilder;
import io.fabric8.tekton.pipeline.v1.PipelineRun;
import io.fabric8.tekton.pipeline.v1.PipelineRunBuilder;
import io.fabric8.tekton.pipeline.v1.PipelineTaskRunSpec;
import io.fabric8.tekton.pipeline.v1.PipelineTaskRunSpecBuilder;
import io.fabric8.tekton.pipeline.v1.TaskRunStepSpecBuilder;
import io.fabric8.tekton.pipeline.v1.WorkspaceBinding;
import io.fabric8.tekton.pipeline.v1.WorkspaceBindingBuilder;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import picocli.CommandLine;

/**
 * Experiment only - see if Fabric8 can be used to create the entire pipelinerun object rather
 * than reading a definition from yaml.
 */
@Deprecated
@CommandLine.Command(name = "fabric8", mixinStandardHelpOptions = true, description = "Creates a pipeline")
public class Fabric8 extends Base implements Runnable {

    @Override
    public void run() {

        PipelineRun run;

        try (InstanceHandle<TektonClient> instanceHandle = Arc.container().instance(TektonClient.class)) {

            // Experiment with creating gitlab project-ncl/konflux-integration/-/blob/main/deploy/mw-pipeline-run-v0.1.yaml
            PipelineRunBuilder pipelineRunBuilder = new PipelineRunBuilder()
                    .withNewMetadata().withGenerateName("hacbs-pipeline-").endMetadata()
                    .withNewSpec()
                    .withNewPipelineRef().withResolver("git").withParams(getGitParams()).endPipelineRef()
                    .withWorkspaces(getWorkspace())
                    .withParams(embedParams())
                    .withTaskRunSpecs(configureTaskRunSpecs())
                    .endSpec();
            run = pipelineRunBuilder.build();
        }
        try (InstanceHandle<KubernetesClient> instanceHandle = Arc.container().instance(KubernetesClient.class)) {
            PipelineRun created = instanceHandle.get().resource(run).create();
            System.err.println("### run created : " + created);
            //            final CountDownLatch closeLatch = new CountDownLatch(1);
            //            instanceHandle.get().resource(run).watch(new Watcher<>() {
            //                @Override
            //                public void eventReceived(Action action, PipelineRun resource) {
            //                    System.out.println("### event action " + action.name());
            //                    switch (action.name()) {
            //                        case "ADDED":
            //                            System.out.println("### added " + resource.getMetadata().getName());
            //                            break;
            //                        case "DELETED":
            //                            break;
            //                        case "MODIFIED":
            //                            System.out.println(
            //                                "### added " + resource.getMetadata().getName() + " and status " + resource.getStatus()
            //                                    .getResults());
            //                            break;
            //                        // default:
            //                    }
            //                }
            //
            //                @Override
            //                public void onClose(WatcherException cause) {
            //                    System.out.println("### close " + cause);
            //                    closeLatch.countDown();
            //                }
            //            });
            //            closeLatch.await();
            //        } catch (InterruptedException e) {
            //            throw new RuntimeException(e);

            //            created.getStatus()
        }
    }

    private List<Param> embedParams() {
        List<Param> result = new ArrayList<>();
        // The actual parameters to be customized...
        result.add(new ParamBuilder().withName("URL").withNewValue(url).build());
        result.add(new ParamBuilder().withName("REVISION").withNewValue(revision).build());
        result.add(new ParamBuilder().withName("BUILD_TOOL").withNewValue(buildTool).build());
        result.add(new ParamBuilder().withName("BUILD_TOOL_VERSION").withNewValue(buildToolVersion).build());
        result.add(new ParamBuilder().withName("JAVA_VERSION").withNewValue(javaVersion).build());
        result.add(new ParamBuilder().withName("BUILD_SCRIPT").withNewValue(buildScript).build());
        if (accessToken.isPresent()) {
            result.add(new ParamBuilder().withName("ACCESS_TOKEN").withNewValue(accessToken.get()).build());
        } else {
            System.err.println("Access token not set");
        }
        // TODO: Hard code these per now, same as in pipelinerun yaml
        result.add(new ParamBuilder().withName("MVN_REPO_DEPLOY_URL").withNewValue(deploy)
                .build());
        result.add(new ParamBuilder().withName("MVN_REPO_DEPENDENCIES_URL").withNewValue(deploy)
                .build());
        result.add(new ParamBuilder().withName("BUILD_ID").withNewValue("test-maven-konflux-int-0001").build());

        return result;
    }

    // TODO: The memory settings in this function should be customizable for different build sizes
    private List<PipelineTaskRunSpec> configureTaskRunSpecs() {
        var stepSpec = new PipelineTaskRunSpecBuilder().withPipelineTaskName("buildah-oci-ta")
                .withStepSpecs(new TaskRunStepSpecBuilder()
                        .withName("build")
                        .withComputeResources(new ResourceRequirementsBuilder()
                                .withLimits(Collections.singletonMap("memory", new Quantity("5Gi")))
                                .withRequests(Collections.singletonMap("memory", new Quantity("5Gi"))).build())
                        .build())
                .build();
        return Collections.singletonList(stepSpec);
    }

    private List<Param> getGitParams() {
        List<Param> result = new ArrayList<>();
        result.add(new ParamBuilder().withName("url")
                .withNewValue("https://gitlab.cee.redhat.com/project-ncl/konflux-integration.git").build());
        result.add(new ParamBuilder().withName("revision").withNewValue("main").build());
        result.add(new ParamBuilder().withName("pathInRepo").withNewValue(".tekton/mw-pipeline-v0.1.yaml").build());
        return result;
    }

    private WorkspaceBinding getWorkspace() {
        return new WorkspaceBindingBuilder().withName("source").withNewVolumeClaimTemplate()
                .withNewSpec()
                .addToAccessModes("ReadWriteOnce")
                .withNewResources().withRequests(Collections.singletonMap("storage", new Quantity("1Gi"))).endResources()
                .endSpec()
                .endVolumeClaimTemplate()
                .build();
    }
}

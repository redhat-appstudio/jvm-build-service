package io.github.redhatappstudio.jvmbuild.cli;

import java.time.OffsetDateTime;
import java.util.Map;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildSpec;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuildStatus;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuildSpec;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuildStatus;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildspec.Scm;

import io.fabric8.knative.internal.pkg.apis.Condition;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.api.model.RouteSpecBuilder;
import io.fabric8.tekton.pipeline.v1beta1.ChildStatusReferenceBuilder;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRun;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRunStatusBuilder;
import io.fabric8.tekton.pipeline.v1beta1.TaskRun;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;

@WithKubernetesTestServer
abstract public class TestComponentManager {

    @KubernetesTestServer
    protected KubernetesServer server;

    protected ArtifactBuild createArtifactBuild(String name, String gav, String artifactBuildState) {
        ArtifactBuild result = new ArtifactBuild();
        result.setMetadata(new ObjectMetaBuilder().withName(name).build());
        ArtifactBuildStatus status = new ArtifactBuildStatus();
        status.setState(artifactBuildState);
        result.setStatus(status);
        ArtifactBuildSpec abs = new ArtifactBuildSpec();
        abs.setGav(gav);
        result.setSpec(abs);
        return result;
    }

    protected DependencyBuild createDependencyBuild(ArtifactBuild ab, String name, String ownerReference,
            String version, String scmHash, String scmURL, String tag,
            String dependencyBuildState) {
        DependencyBuild result = new DependencyBuild();
        result.setMetadata(new ObjectMetaBuilder().withName(name)
                .withOwnerReferences(new OwnerReferenceBuilder().withName(ownerReference).withKind("ArtifactBuild")
                        .withUid(ab.getMetadata().getUid()).build())
                .build());
        DependencyBuildStatus status = new DependencyBuildStatus();
        status.setState(dependencyBuildState);
        result.setStatus(status);
        DependencyBuildSpec dbs = new DependencyBuildSpec();
        Scm scm = new Scm();
        scm.setScmType("git");
        scm.setScmURL(scmURL);
        scm.setTag(tag);
        scm.setCommitHash(scmHash);
        dbs.setScm(scm);
        dbs.setVersion(version);
        result.setSpec(dbs);
        return result;
    }

    protected PipelineRun createPipelineRun(DependencyBuild db, String pipelineRunSuffix) {
        PipelineRun result = new PipelineRun();
        String name = db.getMetadata().getName();
        result.setMetadata(new ObjectMetaBuilder().withName(name + pipelineRunSuffix).withOwnerReferences(
                new OwnerReferenceBuilder()
                        .withName(name)
                        .withKind("DependencyBuild")
                        .withUid(db.getMetadata().getUid()).build())
                .build());

        String startTime = OffsetDateTime.now().toString();
        String completionTime = OffsetDateTime.now().toString();
        result.setStatus(new PipelineRunStatusBuilder()
                .withCompletionTime(completionTime)
                .withStartTime(startTime)
                .withConditions(
                        new Condition(completionTime, "Tasks completed", "Succeeded", "", "True", "Succeeded"))
                .build());

        return result;
    }

    protected TaskRun createTaskRun(PipelineRun pr, String taskName) {
        TaskRun result = new TaskRun();
        result.setMetadata(new ObjectMetaBuilder().withName(pr.getMetadata().getName() + taskName).build());

        pr.getStatus().getChildReferences().add(
                new ChildStatusReferenceBuilder().withName(result.getMetadata().getName()).withPipelineTaskName(taskName)
                        .withKind("TaskRun").build());

        return result;
    }

    protected Pod createNewPod(TaskRun taskRun, String name, Map<String, String> labels) {

        ContainerStatus containerStatus = new ContainerStatusBuilder()
                .withName(name)
                .withState(
                        new ContainerStateBuilder().withTerminated(new ContainerStateTerminatedBuilder().withReason("Completed")
                                .withFinishedAt(OffsetDateTime.now().toString()).build()).build())
                .build();

        return new PodBuilder()
                .withNewMetadata()
                .withName(taskRun.getMetadata().getName() + "-pod")
                .addToLabels(labels)
                .withOwnerReferences(new OwnerReferenceBuilder()
                        .withName(taskRun.getMetadata().getName())
                        .withKind("TaskRun").build())
                .endMetadata()
                .withStatus(new PodStatusBuilder().withContainerStatuses(containerStatus).build())
                .build();
    }

    protected Route createRoute() {
        return new RouteBuilder().withMetadata(new ObjectMetaBuilder().withName("tekton-results").build())
                .withSpec(new RouteSpecBuilder().withHost("localhost").build()).build();
    }
}

package io.github.redhatappstudio.jvmbuild.cli.builds;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRun;
import io.fabric8.tekton.pipeline.v1beta1.TaskRun;
import io.github.redhatappstudio.jvmbuild.cli.artifacts.ArtifactBuildCompleter;
import io.github.redhatappstudio.jvmbuild.cli.artifacts.GavCompleter;
import io.github.redhatappstudio.jvmbuild.cli.util.BuildConverter;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "logs", mixinStandardHelpOptions = true, description = "Displays the logs for the build")
public class BuildLogsCommand implements Runnable {

    @CommandLine.Option(names = "-g", description = "The build to view, specified by GAV", completionCandidates = GavCompleter.class)
    String gav;

    @CommandLine.Option(names = "-a", description = "The build to view, specified by ArtifactBuild name", completionCandidates = ArtifactBuildCompleter.class)
    String artifact;

    @CommandLine.Option(names = "-b", description = "The build to view, specified by build id", completionCandidates = BuildCompleter.class)
    String build;

    @CommandLine.Option(names = "-n", description = "The build number", defaultValue = "-1")
    int buildNo;

    @Override
    public void run() {
        var client = Arc.container().instance(KubernetesClient.class).get();
        DependencyBuild theBuild = null;
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

        List<Integer> buildNumbers = new ArrayList<>();
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
        }

        System.out.println("Selected build: " + theBuild.getMetadata().getName());

        for (var buildNo : buildNumbers) {
            var pr = client.resources(PipelineRun.class).withName(theBuild.getMetadata().getName() + "-build-" + buildNo);
            if (pr == null || pr.get() == null) {
                System.out.println("PipelineRun not found so unable to extract logs.");
                return;
            }
            PipelineRun pipelineRun = pr.get();
            if (pipelineRun.getStatus().getCompletionTime() == null) {
                System.out.println("PipelineRun not finished.");
                continue;
            }
            boolean success = false;
            for (var i : pipelineRun.getStatus().getConditions()) {
                if (Objects.equals("Succeeded", i.getType())) {
                    if (i.getStatus().toLowerCase(Locale.ROOT).equals("true")) {
                        success = true;
                    }
                }
            }

            System.out.println("---------   Logs for PipelineRun " + pipelineRun.getMetadata().getName() + " ("
                    + (success ? "SUCCEEDED" : "FAILED") + ") ---------");
            var references = pipelineRun.getStatus().getTaskRuns().keySet();
            List<TaskRun> taskRuns = new ArrayList<>();
            for (var ref : references) {
                var tr = client.resources(TaskRun.class).withName(ref);
                if (tr == null || tr.get() == null) {
                    System.out.println("TaskRun " + ref + " not found so unable to extract logs.");
                } else {
                    taskRuns.add(tr.get());
                }
            }
            if (taskRuns.isEmpty()) {
                System.out.println("No TaskRuns found");
                continue;
            }
            DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

            taskRuns.sort(Comparator.comparing(t -> OffsetDateTime.parse(t.getStatus().getStartTime(), formatter)));

            OffsetDateTime startTime = OffsetDateTime.parse(pipelineRun.getStatus().getStartTime(), formatter);
            System.out.println("\n\n#####################################################");
            for (var tr : taskRuns) {

                var pod = client.pods().withName(tr.getMetadata().getName() + "-pod");
                if (pod == null || pod.get() == null) {
                    System.out.println("Pod not found for task  " + tr.getMetadata().getName() + " so unable to extract logs.");
                    continue;
                }

                List<ContainerStatus> containerStatuses = new ArrayList<>(pod.get().getStatus().getContainerStatuses());
                containerStatuses.sort(
                        Comparator
                                .comparing(t -> OffsetDateTime.parse(t.getState().getTerminated().getFinishedAt(), formatter)));
                for (var i : containerStatuses) {
                    var p = pod.inContainer(i.getName());

                    System.out.println(
                            "    Logs for container " + i.getName());
                    System.out.println("#####################################################\n\n");
                    try (var w = p.watchLog(); var in = w.getOutput()) {
                        int r;
                        byte[] buff = new byte[1024];
                        while ((r = in.read(buff)) > 0) {
                            System.out.print(new String(buff, 0, r));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    var finishTime = OffsetDateTime.parse(i.getState().getTerminated().getFinishedAt(), formatter);
                    Duration duration = Duration.between(startTime, finishTime);
                    startTime = finishTime;
                    System.out.println("\n\n#####################################################");
                    System.out.println(
                            "    Container " + i.getName() + " finished at " + i.getState().getTerminated().getFinishedAt()
                                    + " in " + duration.getSeconds() + " seconds");
                }
            }
            System.out.println("#####################################################\n\n");
        }

    }

    private void throwUnspecified() {
        throw new RuntimeException("Can only specify one of -b, -a or -g");
    }
}

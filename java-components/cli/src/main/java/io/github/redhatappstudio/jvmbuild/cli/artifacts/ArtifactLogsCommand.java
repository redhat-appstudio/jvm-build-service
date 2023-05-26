package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import java.io.IOException;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "logs", mixinStandardHelpOptions = true, description = "Displays the logs for artifact discovery pipelines")
public class ArtifactLogsCommand implements Runnable {

    @CommandLine.Option(names = "-g", description = "The build to view, specified by GAV", completionCandidates = GavCompleter.class)
    String gav;

    @CommandLine.Option(names = "-a", description = "The build to view, specified by ArtifactBuild name", completionCandidates = ArtifactBuildCompleter.class)
    String artifact;

    @Override
    public void run() {
        var client = Arc.container().instance(KubernetesClient.class).get();
        ArtifactBuild theBuild = null;
        if (artifact != null) {
            if (gav != null) {
                throwUnspecified();
            }
            theBuild = ArtifactBuildCompleter.createNames().get(artifact);
        } else if (gav != null) {
            theBuild = GavCompleter.createNames().get(gav);
        } else {
            throw new RuntimeException("Must specify one of -a or -g");
        }
        if (theBuild == null) {
            throw new RuntimeException("Artifact not found");
        }
        System.out.println("Selected artifact: " + theBuild.getMetadata().getName());

        var pods = client.pods().list().getItems();
        for (var pod : pods) {
            //list all pods related to this artifact build and output the contents
            if (pod.getMetadata().getName().contains(theBuild.getMetadata().getName())) {
                for (var i : pod.getSpec().getContainers()) {
                    System.out.println("Pod: " + pod.getMetadata().getName() + " Container: " + i);
                    System.out.println("--------------------------------------");
                    var p = client.pods().withName(pod.getMetadata().getName()).inContainer(i.getName());
                    try (var w = p.watchLog(); var in = w.getOutput()) {
                        int r;
                        byte[] buff = new byte[1024];
                        while ((r = in.read(buff)) > 0) {
                            System.out.print(new String(buff, 0, r));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

    }

    private void throwUnspecified() {
        throw new RuntimeException("Can only specify one of -a or -g");
    }
}

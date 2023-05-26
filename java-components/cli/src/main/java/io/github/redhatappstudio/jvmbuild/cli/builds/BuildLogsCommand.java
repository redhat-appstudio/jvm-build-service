package io.github.redhatappstudio.jvmbuild.cli.builds;

import java.io.IOException;
import java.util.Map;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
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

    @CommandLine.Option(names = "-n", description = "The build number")
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
        System.out.println("Selected build: " + theBuild.getMetadata().getName());

        var pod = client.pods().withName(theBuild.getMetadata().getName() + "-build-" + buildNo + "-task-pod");
        if (pod == null || pod.get() == null) {
            System.out.println("Pod not found so unable to extract logs.");
            return;
        }
        for (var i : pod.get().getSpec().getContainers()) {
            var p = pod.inContainer(i.getName());
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

    private void throwUnspecified() {
        throw new RuntimeException("Can only specify one of -b, -a or -g");
    }
}

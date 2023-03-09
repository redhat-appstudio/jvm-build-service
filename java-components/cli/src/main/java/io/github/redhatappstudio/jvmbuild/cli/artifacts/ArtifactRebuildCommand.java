package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import java.util.HashMap;
import java.util.function.UnaryOperator;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "rebuild", mixinStandardHelpOptions = true, description = "Triggers a rebuild of an artifact")
public class ArtifactRebuildCommand implements Runnable {

    @CommandLine.Option(names = "-g", description = "The artifact to rebuild, specified by GAV", completionCandidates = GavCompleter.class)
    String gav;

    @CommandLine.Option(names = "-a", description = "The artifact to rebuild, specified by ArtifactBuild name", completionCandidates = ArtifactBuildCompleter.class)
    String artifact;

    @CommandLine.Option(names = "--failed", description = "Rebuild all failed artifacts")
    boolean failed;

    @Override
    public void run() {
        if (failed) {
            if (gav != null || artifact != null) {
                throw new RuntimeException("Must specify one of -a or -g or --failed");
            }
            var client = Arc.container().instance(KubernetesClient.class).get();
            for (var ab : client.resources(ArtifactBuild.class).list().getItems()) {
                if (!ab.getStatus().getState().equals(ArtifactBuild.COMPLETE)) {
                    System.out.println("Rebuilding: " + ab.getMetadata().getName());
                    client.resources(ArtifactBuild.class).withName(ab.getMetadata().getName())
                            .edit(new UnaryOperator<ArtifactBuild>() {
                                @Override
                                public ArtifactBuild apply(ArtifactBuild artifactBuild) {
                                    if (artifactBuild.getMetadata().getAnnotations() == null) {
                                        artifactBuild.getMetadata().setAnnotations(new HashMap<>());
                                    }
                                    artifactBuild.getMetadata().getAnnotations().put("jvmbuildservice.io/rebuild", "failed");
                                    return artifactBuild;
                                }
                            });
                }
            }
            return;
        }
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
            throw new RuntimeException("Must specify one of -a or -g or --failed");
        }
        if (theBuild == null) {
            throw new RuntimeException("Artifact not found");
        }
        System.out.println("Rebuilding: " + theBuild.getMetadata().getName());

        client.resources(ArtifactBuild.class).withName(theBuild.getMetadata().getName())
                .edit(new UnaryOperator<ArtifactBuild>() {
                    @Override
                    public ArtifactBuild apply(ArtifactBuild artifactBuild) {
                        if (artifactBuild.getMetadata().getAnnotations() == null) {
                            artifactBuild.getMetadata().setAnnotations(new HashMap<>());
                        }
                        artifactBuild.getMetadata().getAnnotations().put("jvmbuildservice.io/rebuild", "true");
                        return artifactBuild;
                    }
                });
    }

    private void throwUnspecified() {
        throw new RuntimeException("Can only specify one of -a, -g or --failed");
    }
}

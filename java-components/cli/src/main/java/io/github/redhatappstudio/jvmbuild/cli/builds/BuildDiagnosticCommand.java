package io.github.redhatappstudio.jvmbuild.cli.builds;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.artifacts.ArtifactBuildCompleter;
import io.github.redhatappstudio.jvmbuild.cli.artifacts.GavCompleter;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "diagnostic", mixinStandardHelpOptions = true, description = "Retrieves diagnostic "
        + "Dockerfiles for a build")
public class BuildDiagnosticCommand
        implements Runnable {

    @CommandLine.Option(names = "-g", description = "The build to retrieve, specified by GAV", completionCandidates = GavCompleter.class)
    String gav;

    @CommandLine.Option(names = "-a", description = "The build to retrieve, specified by ArtifactBuild name", completionCandidates = ArtifactBuildCompleter.class)
    String artifact;

    @CommandLine.Option(names = "-b", description = "The build to retrieve, specified by build id", completionCandidates = BuildCompleter.class)
    String build;

    @CommandLine.Option(names = "-d", description = "Directory to save the Dockerfiles to. Defaults to current directory.", completionCandidates = BuildCompleter.class)
    File targetDirectory = new File(System.getProperty("user.dir"));

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
            theBuild = buildToArtifact(client, ab);
        } else if (gav != null) {
            ArtifactBuild ab = GavCompleter.createNames().get(gav);
            theBuild = buildToArtifact(client, ab);
        } else {
            throw new RuntimeException("Must specify one of -b, -a or -g");
        }
        if (theBuild == null) {
            throw new RuntimeException("Build not found");
        }
        int lastSlash = theBuild.getSpec().getScm().getScmURL().lastIndexOf('/') + 1;
        String name = theBuild.getSpec().getScm().getScmURL().substring(lastSlash).replaceFirst("\\.git.*", "");
        System.out.println("Target directory: " + targetDirectory);
        System.out.println(
                "Selected build: " + theBuild.getMetadata().getName() + " of " + theBuild.getSpec().getScm().getScmURL() + ':' +
                        theBuild.getSpec().getVersion());

        List<String> dockerFiles = theBuild.getStatus().getDiagnosticDockerFiles();
        if ((theBuild.getStatus().getFailedBuildRecipes() == null ? 0 : theBuild.getStatus().getFailedBuildRecipes().size())
                + 1 != dockerFiles.size()) {
            throw new RuntimeException("Mismatch between failed/current build recipe count and number of Dockerfiles.");
        }
        try {
            for (int i = 0; i < dockerFiles.size(); i++) {
                String fileName;
                if (i == dockerFiles.size() - 1) {
                    // Final one - which is successful.
                    fileName = "Dockerfile." + name + ".succeed.jdk" + theBuild.getStatus()
                            .getCurrentBuildRecipe()
                            .getJavaVersion();
                } else {
                    fileName = "Dockerfile." + name + ".failed.jdk" + theBuild.getStatus()
                            .getFailedBuildRecipes()
                            .get(i)
                            .getJavaVersion();
                }
                System.out.println("Writing " + fileName);
                Files.writeString(Paths.get(targetDirectory.toString(), fileName),
                        dockerFiles.get(i));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write Dockerfile", e);
        }
    }

    // TODO: Create utility class and move these (and their duplicates to that)
    private DependencyBuild buildToArtifact(KubernetesClient client, ArtifactBuild ab) {
        if (ab == null) {
            return null;
        }
        for (var i : client.resources(DependencyBuild.class).list().getItems()) {
            Optional<OwnerReference> ownerReferenceFor = i.getOwnerReferenceFor(ab);
            if (ownerReferenceFor.isPresent()) {
                return i;
            }
        }
        return null;
    }

    private void throwUnspecified() {
        throw new RuntimeException("Can only specify one of -b, -a or -g");
    }
}

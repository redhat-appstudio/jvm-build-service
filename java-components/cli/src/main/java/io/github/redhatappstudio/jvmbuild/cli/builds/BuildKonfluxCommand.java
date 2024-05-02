package io.github.redhatappstudio.jvmbuild.cli.builds;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import com.redhat.hacbs.common.tools.completer.BuildCompleter;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.artifacts.ArtifactBuildCompleter;
import io.github.redhatappstudio.jvmbuild.cli.artifacts.GavCompleter;
import io.github.redhatappstudio.jvmbuild.cli.util.BuildConverter;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "konflux", mixinStandardHelpOptions = true, description = "Retrieves Containerfile and script for building in Konflux")
public class BuildKonfluxCommand
        implements Runnable {

    @CommandLine.Option(names = "-g", description = "The build to retrieve, specified by GAV", completionCandidates = GavCompleter.class)
    String gav;

    @CommandLine.Option(names = "-a", description = "The build to retrieve, specified by ArtifactBuild name", completionCandidates = ArtifactBuildCompleter.class)
    String artifact;

    @CommandLine.Option(names = "-b", description = "The build to retrieve, specified by build id", completionCandidates = BuildCompleter.class)
    String build;

    @CommandLine.Option(names = "-d", description = "Directory to save the Containerfiles and script to. Defaults to current directory.", completionCandidates = BuildCompleter.class)
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
        int lastSlash = theBuild.getSpec().getScm().getScmURL().lastIndexOf('/') + 1;
        String name = theBuild.getSpec().getScm().getScmURL().substring(lastSlash).replaceFirst("\\.git.*", "");
        System.out.println("Target directory: " + targetDirectory);
        System.out.println(
                "Selected build: " + theBuild.getMetadata().getName() + " of " + theBuild.getSpec().getScm().getScmURL() + ':' +
                        theBuild.getSpec().getVersion() + '\n');

        var builds = theBuild.getStatus().getBuildAttempts();
        if (builds == null) {
            System.out.println("No build attempts have been made");
            return;
        }
        try {
            int counter = 0;
            for (var i : builds) {
                String containerFileName = "Containerfile";
                String javaVersion;
                String tagName;
                if (Boolean.TRUE.equals(i.getBuild().getSucceeded())) {
                    javaVersion = i.getBuildRecipe().getJavaVersion();
                    tagName = name + ".succeed.jdk" + javaVersion;
                } else {
                    javaVersion = i.getBuildRecipe().getJavaVersion();
                    tagName = name + ".failed.jdk" + javaVersion;
                }
                tagName = tagName + "-" + counter++;
                String directoryName = "konflux." + tagName;
                tagName = "localhost/" + tagName;
                System.out.println(
                        CommandLine.Help.Ansi.AUTO
                                .string(
                                        """
                                                @|green For this JDK%s build, move the contents of %s to the appropriate .jbs directory and then run:

                                                |@@|yellow podman build -f .jbs/%s -t %s .
                                                podman run -it %s|@
                                                """
                                                .formatted(javaVersion, directoryName, containerFileName,
                                                        tagName, tagName)));

                Files.createDirectories(Paths.get(targetDirectory.toString(), directoryName));
                Path scriptFile = Paths.get(targetDirectory.toString(), directoryName, "run-build.sh");
                Files.writeString(Paths.get(targetDirectory.toString(), directoryName, containerFileName),
                        i.getBuild().getKonfluxDockerFile());
                Files.writeString(scriptFile, i.getBuild().getKonfluxScriptFile());
                scriptFile.toFile().setExecutable(true);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write Dockerfile", e);
        }
    }

    private void throwUnspecified() {
        throw new RuntimeException("Can only specify one of -b, -a or -g");
    }
}

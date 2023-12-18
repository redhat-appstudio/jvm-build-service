package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import java.util.Map;

import com.redhat.hacbs.common.tools.recipes.ModifyScmRepoCommand;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.Quarkus;
import picocli.CommandLine;

@CommandLine.Command(name = "fix-missing", mixinStandardHelpOptions = true, description = "Creates a pull request against the recipe database that adds SCM information for an artifact.")
public class ArtifactFixMissingCommand implements Runnable {

    @CommandLine.Option(names = "-g", description = "The artifact to rebuild, specified by GAV", completionCandidates = MissingGavCompleter.class)
    String targetGav;

    @CommandLine.Option(names = "-a", description = "The artifact to rebuild, specified by ArtifactBuild name", completionCandidates = MissingArtifactBuildCompleter.class)
    String artifact;

    @CommandLine.Option(names = "--group-level", description = "Apply this SCM information at the group level")
    boolean group;

    @CommandLine.Option(names = "--version-level", description = "Apply this SCM information at to a specific version")
    boolean version;

    @CommandLine.Option(names = "--uri", description = "The SCM URI")
    String uri;

    @CommandLine.Option(names = "--legacy", description = "Add this information to the legacyRepos section of the file")
    boolean legacy;

    @CommandLine.Option(names = "--path", description = "The path into the repo")
    String path;

    @CommandLine.Option(names = "--tag-mapping", description = "A tag mapping in the form of pattern=tag")
    String tagMapping;

    @Override
    public void run() {
        if (legacy && uri == null) {
            System.err.println("You must specify a URI when using --legacy");
            Quarkus.asyncExit(1);
            return;
        }
        if (tagMapping != null) {
            if (!tagMapping.contains("=")) {
                System.err.println("Tag mapping must be in the form of pattern=tag");
                Quarkus.asyncExit(1);
                return;
            }
        }
        var client = Arc.container().instance(KubernetesClient.class).get();
        String gav = null;
        if (artifact != null) {
            if (targetGav != null) {
                throwUnspecified();
            }
            ArtifactBuild artifactBuild = ArtifactBuildCompleter.createNames().get(artifact);
            if (artifactBuild == null) {
                throw new RuntimeException("Artifact not found: " + artifact);
            }
            gav = artifactBuild.getSpec().getGav();
        } else if (targetGav != null) {
            gav = targetGav;
        } else {
            throw new RuntimeException("Must specify one of -a or -g");
        }
        new ModifyScmRepoCommand(gav)
                .setGroup(group)
                .setLegacy(legacy)
                .setVersion(version)
                .setUri(uri)
                .setTagMapping(handleTagMapping(tagMapping))
                .setPath(path)
                .run();
    }

    private Map<String, String> handleTagMapping(String tagMapping) {
        if (tagMapping == null) {
            return Map.of();
        }
        String[] parts = tagMapping.split("=");
        if (parts.length != 2) {
            throw new RuntimeException("Tag mapping must be in the form pattern=tag");
        }
        return Map.of(parts[0], parts[1]);
    }

    private void throwUnspecified() {
        throw new RuntimeException("Can only specify one of -a or -g");
    }
}

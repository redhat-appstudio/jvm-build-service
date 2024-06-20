package com.redhat.hacbs.cli.builds;

import java.util.Map;
import java.util.function.Function;

import com.redhat.hacbs.cli.artifacts.ArtifactBuildCompleter;
import com.redhat.hacbs.cli.artifacts.GavCompleter;
import com.redhat.hacbs.cli.util.BuildConverter;
import com.redhat.hacbs.common.tools.completer.BuildCompleter;
import com.redhat.hacbs.common.tools.recipes.ModifyBuildRecipeCommand;
import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

/**
 * A command that can be used to create a PR against the build recipe database
 */
@CommandLine.Command(name = "fix", mixinStandardHelpOptions = true, description = "Creates a pull request against the recipe information to fix a specific build.")
public class BuildFixCommand implements Runnable {

    @CommandLine.Option(names = "-g", description = "The build to view, specified by GAV", completionCandidates = GavCompleter.class)
    String gav;

    @CommandLine.Option(names = "-a", description = "The build to view, specified by ArtifactBuild name", completionCandidates = ArtifactBuildCompleter.class)
    String artifact;

    @CommandLine.Option(names = "-b", description = "The build to view, specified by build id", completionCandidates = BuildCompleter.class)
    String build;

    @CommandLine.Option(names = "--enforce-version", description = "Sets enforce-version on the build recipe")
    Boolean enforceVersion;

    @CommandLine.Option(names = "--version-level", description = "If this should only be applied to this specific version (and earlier).")
    boolean versionSpecific;

    @CommandLine.Option(names = "--add-repository", description = "Adds a maven repository to the build")
    String addRepository;

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
        if (!enforceVersion) {
            throw new RuntimeException("--enforce-version is the only flag supported at the moment");
        }

        new ModifyBuildRecipeCommand(theBuild, new Function<BuildRecipeInfo, BuildRecipeInfo>() {
            @Override
            public BuildRecipeInfo apply(BuildRecipeInfo buildRecipe) {
                if (enforceVersion != null) {
                    buildRecipe.setEnforceVersion(enforceVersion);
                }
                if (addRepository != null) {
                    buildRecipe.getRepositories().add(addRepository);
                }
                return buildRecipe;
            }
        })
                .setVersionSpecific(versionSpecific)
                .run();

    }

    private void throwUnspecified() {
        throw new RuntimeException("Can only specify one of -b, -a or -g");
    }
}

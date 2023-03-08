package io.github.redhatappstudio.jvmbuild.cli.builds;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.build.AddBuildRecipeRequest;
import com.redhat.hacbs.recipies.build.BuildRecipeInfo;
import com.redhat.hacbs.recipies.location.BuildInfoRequest;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuildSpec;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.artifacts.ArtifactBuildCompleter;
import io.github.redhatappstudio.jvmbuild.cli.artifacts.GavCompleter;
import io.github.redhatappstudio.jvmbuild.cli.repo.RepositoryChange;
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
    boolean enforceVersion;

    @CommandLine.Option(names = "--version-level", description = "If this should only be applied to this specific version (and earlier).")
    boolean versionSpecific;

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
        System.out.println("Selected build: " + theBuild.getMetadata().getName());
        if (!enforceVersion) {
            throw new RuntimeException("--enforce-version is the only flag supported at the moment");
        }

        DependencyBuildSpec buildSpec = theBuild.getSpec();
        String branchName = "branch-" + System.currentTimeMillis(); //TODO: better branch names
        String message = "Updated build-info for " + buildSpec.getScm().getScmURL();

        RepositoryChange.createPullRequest(branchName, message, (repositoryRoot, groupManager, recipeLayoutManager) -> {
            var existing = groupManager
                    .requestBuildInformation(new BuildInfoRequest(buildSpec.getScm().getScmURL(), buildSpec.getVersion(),
                            Set.of(BuildRecipe.BUILD)));
            BuildRecipeInfo buildRecipe = null;
            if (existing != null && existing.getData().containsKey(BuildRecipe.BUILD)) {
                buildRecipe = BuildRecipe.BUILD.getHandler().parse(existing.getData().get(BuildRecipe.BUILD));
            } else {
                buildRecipe = new BuildRecipeInfo();
            }
            buildRecipe.setEnforceVersion(enforceVersion);
            recipeLayoutManager.writeBuildData(new AddBuildRecipeRequest<>(BuildRecipe.BUILD, buildRecipe,
                    buildSpec.getScm().getScmURL(), versionSpecific ? buildSpec.getVersion() : null));
        });

    }

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

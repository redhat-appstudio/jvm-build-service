package com.redhat.hacbs.common.tools.recipes;

import java.util.Set;
import java.util.function.Function;

import com.redhat.hacbs.common.tools.repo.RepositoryChange;
import com.redhat.hacbs.recipes.BuildRecipe;
import com.redhat.hacbs.recipes.build.AddBuildRecipeRequest;
import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.recipes.location.BuildInfoRequest;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuildSpec;

public class ModifyBuildRecipeCommand {

    final DependencyBuild theBuild;
    boolean versionSpecific;

    final Function<BuildRecipeInfo, BuildRecipeInfo> editFunction;

    public ModifyBuildRecipeCommand(DependencyBuild theBuild, Function<BuildRecipeInfo, BuildRecipeInfo> editFunction) {
        this.theBuild = theBuild;
        this.editFunction = editFunction;
    }

    public void run() {

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
            buildRecipe = editFunction.apply(buildRecipe);

            for (var addRepository : buildRecipe.getRepositories()) {
                if (recipeLayoutManager.getRepositoryPaths(addRepository).isEmpty()) {
                    throw new IllegalArgumentException("Unknown repository " + addRepository);
                }
            }
            recipeLayoutManager.writeBuildData(new AddBuildRecipeRequest<>(BuildRecipe.BUILD, buildRecipe,
                    buildSpec.getScm().getScmURL(), versionSpecific ? buildSpec.getVersion() : null));
        });
    }

    public ModifyBuildRecipeCommand setVersionSpecific(boolean versionSpecific) {
        this.versionSpecific = versionSpecific;
        return this;
    }
}

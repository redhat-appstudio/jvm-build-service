package com.redhat.hacbs.common.tools.recipes;

import static com.redhat.hacbs.recipes.location.RecipeRepositoryManager.BUILD_INFO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import com.redhat.hacbs.common.tools.repo.RepositoryChange;
import com.redhat.hacbs.recipes.BuildRecipe;
import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.recipes.location.RecipeGroupManager;
import com.redhat.hacbs.recipes.location.RecipeLayoutManager;
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

    public String run() {

        DependencyBuildSpec buildSpec = theBuild.getSpec();
        String branchName = "branch-" + System.currentTimeMillis(); //TODO: better branch names
        String message = "Updated build-info for " + buildSpec.getScm().getScmURL();
        String target = BUILD_INFO + "/" + RecipeGroupManager.normalizeScmUri(buildSpec.getScm().getScmURL());
        if (versionSpecific) {
            target += "/" + RecipeLayoutManager.VERSION + "/" + buildSpec.getVersion();
        }
        try {

            var existing = RepositoryChange.getContent(target);
            BuildRecipeInfo buildRecipe = null;
            if (existing != null) {
                buildRecipe = BuildRecipe.BUILD.getHandler()
                        .parse(new ByteArrayInputStream(existing.getBytes(StandardCharsets.UTF_8)));
            } else {
                buildRecipe = new BuildRecipeInfo();
            }
            buildRecipe = editFunction.apply(buildRecipe);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BuildRecipe.BUILD.getHandler().write(buildRecipe, baos);
            return RepositoryChange.createPullRequest(branchName, message, target, baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ModifyBuildRecipeCommand setVersionSpecific(boolean versionSpecific) {
        this.versionSpecific = versionSpecific;
        return this;
    }
}

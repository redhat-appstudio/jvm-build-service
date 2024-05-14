package com.redhat.hacbs.common.tools.repo;

import static com.redhat.hacbs.recipes.location.RecipeRepositoryManager.BUILD_INFO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.recipes.build.BuildRecipeInfoManager;
import com.redhat.hacbs.recipes.location.RecipeGroupManager;

public class BuildInfoService {

    public static Optional<BuildEditInfo> getBuildInfo(String scmUri) {
        String target = BUILD_INFO + "/" + RecipeGroupManager.normalizeScmUri(scmUri) + "/build.yaml";
        var existing = RepositoryChange.getContent(target);
        if (existing == null) {
            return Optional.empty();
        }
        try {
            BuildRecipeInfo info = new BuildRecipeInfoManager()
                    .parse(new ByteArrayInputStream(existing.getBytes(StandardCharsets.UTF_8)));
            if (info == null) {
                return Optional.empty();
            }
            return Optional.of(new BuildEditInfo(info, scmUri, false));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String writeBuildInfo(BuildEditInfo buildEditInfo) {
        String target = BUILD_INFO + "/" + RecipeGroupManager.normalizeScmUri(buildEditInfo.scmUri) + "/build.yaml";
        String branchName = "branch-" + System.currentTimeMillis(); //TODO: better branch names
        String message = "Updated build-info for " + buildEditInfo.scmUri;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try {
            new BuildRecipeInfoManager().write(buildEditInfo.buildInfo(), data);
            return RepositoryChange.createPullRequest(branchName, message, target, data.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public record BuildEditInfo(@Schema(required = true) BuildRecipeInfo buildInfo, @Schema(required = true) String scmUri,
            @Schema(required = true) boolean version) {

    }
}

package com.redhat.hacbs.common.tools.repo;

import static com.redhat.hacbs.recipes.location.RecipeLayoutManager.ARTIFACT;
import static com.redhat.hacbs.recipes.location.RecipeLayoutManager.VERSION;
import static com.redhat.hacbs.recipes.location.RecipeRepositoryManager.SCM_INFO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.redhat.hacbs.recipes.GAV;
import com.redhat.hacbs.recipes.scm.ScmInfo;
import com.redhat.hacbs.recipes.scm.ScmInfoManager;

public class ScmInfoService {

    public static Optional<ScmEditInfo> getScmInfo(String gav) {
        GAV parsed = GAV.parse(gav);
        boolean group = false;
        boolean version = true;
        String target = getTargetPath(parsed, group, version);
        var existing = RepositoryChange.getContent(target);
        if (existing == null) {
            version = false;
            target = getTargetPath(parsed, group, version);
            existing = RepositoryChange.getContent(target);
        }
        if (existing == null) {
            group = true;
            version = true;
            target = getTargetPath(parsed, group, version);
            existing = RepositoryChange.getContent(target);
        }
        if (existing == null) {
            group = true;
            version = false;
            target = getTargetPath(parsed, group, version);
            existing = RepositoryChange.getContent(target);
        }
        if (existing == null) {
            return Optional.empty();
        }
        try {
            ScmInfo info = new ScmInfoManager().parse(new ByteArrayInputStream(existing.getBytes(StandardCharsets.UTF_8)));
            if (info == null) {
                return Optional.empty();
            }
            return Optional.of(new ScmEditInfo(info, group, version, gav));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String writeScmInfo(ScmEditInfo scmEditInfo) {
        GAV parsed = GAV.parse(scmEditInfo.gav());
        String branchName = "branch-" + System.currentTimeMillis(); //TODO: better branch names
        String message = "Updated scm-info for " + scmEditInfo.gav();
        String target = getTargetPath(parsed, scmEditInfo.group, scmEditInfo.version);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try {
            new ScmInfoManager().write(scmEditInfo.scmInfo(), data);
            return RepositoryChange.createPullRequest(branchName, message, target, data.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getTargetPath(GAV parsed, boolean group, boolean version) {
        String target = SCM_INFO + "/" + parsed.getGroupId().replace('.', '/');
        if (!group) {
            target += "/" + ARTIFACT + "/" + parsed.getArtifactId();
        }
        if (version) {
            target += "/" + VERSION + "/" + parsed.getVersion();
        }
        target += "/scm.yaml";
        return target;
    }

    public record ScmEditInfo(@Schema(required = true) ScmInfo scmInfo, @Schema(required = true) boolean group,
            @Schema(required = true) boolean version, @Schema(required = true) String gav) {

    }
}

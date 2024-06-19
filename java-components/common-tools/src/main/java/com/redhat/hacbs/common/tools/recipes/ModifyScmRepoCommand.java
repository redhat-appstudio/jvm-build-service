package com.redhat.hacbs.common.tools.recipes;

import static com.redhat.hacbs.recipes.location.RecipeLayoutManager.ARTIFACT;
import static com.redhat.hacbs.recipes.location.RecipeLayoutManager.VERSION;
import static com.redhat.hacbs.recipes.location.RecipeRepositoryManager.SCM_INFO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

import com.redhat.hacbs.common.sbom.GAV;
import com.redhat.hacbs.common.tools.repo.RepositoryChange;
import com.redhat.hacbs.recipes.BuildRecipe;
import com.redhat.hacbs.recipes.scm.RepositoryInfo;
import com.redhat.hacbs.recipes.scm.ScmInfo;
import com.redhat.hacbs.recipes.scm.TagMapping;

public class ModifyScmRepoCommand {

    private String gav;

    private boolean group;
    private boolean version;
    private boolean legacy;

    private String uri;

    private String path;

    Map<String, String> tagMapping;

    public ModifyScmRepoCommand(String gav) {
        this.gav = gav;
    }

    public ModifyScmRepoCommand() {
    }

    public String run() {
        GAV parsed = GAV.parse(gav);
        String branchName = "branch-" + System.currentTimeMillis(); //TODO: better branch names
        String message = "Updated scm-info for " + gav;
        String target = SCM_INFO + "/" + parsed.getGroupId().replace('.', '/');
        if (!group) {
            target += "/" + ARTIFACT + "/" + parsed.getArtifactId();
        }
        if (version) {
            target += "/" + VERSION + "/" + parsed.getVersion();
        }
        target += "/scm.yaml";
        try {
            var existing = RepositoryChange.getContent(target);
            ScmInfo scmInfo = null;
            if (existing != null) {
                scmInfo = BuildRecipe.SCM.getHandler()
                        .parse(new ByteArrayInputStream(existing.getBytes(StandardCharsets.UTF_8)));
            } else {
                scmInfo = new ScmInfo();
            }
            if (!legacy) {
                if (existing != null) {
                    if (scmInfo.getUri().equals(uri)) {
                        System.err.println("Provided URI matches existing URI");
                    }
                    if (uri != null) {
                        scmInfo.setUri(uri);
                    }
                    if (path != null) {
                        scmInfo.setPath(path.equals("/") ? null : path);
                    }
                } else {
                    if (uri == null) {
                        throw new RuntimeException("URI not specified, and no existing information");
                    }
                    scmInfo = new ScmInfo("git", uri, path);
                }
                if (tagMapping != null) {
                    for (var e : tagMapping.entrySet()) {
                        if (scmInfo.getTagMapping() == null) {
                            scmInfo.setTagMapping(new ArrayList<>());
                        }
                        scmInfo.getTagMapping().add(new TagMapping().setPattern(e.getKey()).setTag(e.getValue()));
                    }
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                BuildRecipe.SCM.getHandler().write(scmInfo, baos);
                return RepositoryChange.createPullRequest(branchName, message, target, baos.toByteArray());

            } else {
                //legacy mode, we just want to add legacy info to an existing file
                if (existing == null) {
                    throw new RuntimeException("Cannot use --legacy when there is no existing data");
                }
                RepositoryInfo repo = null;
                for (var existingLegacy : scmInfo.getLegacyRepos()) {
                    if (existingLegacy.getUri().equals(uri)) {
                        repo = scmInfo;
                        break;
                    }
                }
                if (repo == null) {
                    scmInfo.getLegacyRepos().add(repo = new RepositoryInfo("git", uri, path));
                }
                if (tagMapping != null) {
                    if (repo.getTagMapping() == null) {
                        repo.setTagMapping(new ArrayList<>());
                    }
                    for (var e : tagMapping.entrySet()) {
                        repo.getTagMapping().add(new TagMapping().setPattern(e.getKey()).setTag(e.getValue()));
                    }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                BuildRecipe.SCM.getHandler().write(scmInfo, baos);
                return RepositoryChange.createPullRequest(branchName, message, target, baos.toByteArray());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public ModifyScmRepoCommand setGroup(boolean group) {
        this.group = group;
        return this;
    }

    public ModifyScmRepoCommand setVersion(boolean version) {
        this.version = version;
        return this;
    }

    public ModifyScmRepoCommand setLegacy(boolean legacy) {
        this.legacy = legacy;
        return this;
    }

    public ModifyScmRepoCommand setUri(String uri) {
        this.uri = uri;
        return this;
    }

    public ModifyScmRepoCommand setPath(String path) {
        this.path = path;
        return this;
    }

    public ModifyScmRepoCommand setTagMapping(Map<String, String> tagMapping) {
        this.tagMapping = tagMapping;
        return this;
    }

    public String getGav() {
        return gav;
    }

    public ModifyScmRepoCommand setGav(String gav) {
        this.gav = gav;
        return this;
    }

    public boolean isGroup() {
        return group;
    }

    public boolean isVersion() {
        return version;
    }

    public boolean isLegacy() {
        return legacy;
    }

    public String getUri() {
        return uri;
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getTagMapping() {
        return tagMapping;
    }
}

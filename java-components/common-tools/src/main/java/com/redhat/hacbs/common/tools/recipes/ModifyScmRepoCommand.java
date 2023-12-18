package com.redhat.hacbs.common.tools.recipes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import com.redhat.hacbs.common.tools.repo.RepositoryChange;
import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.location.AddRecipeRequest;
import com.redhat.hacbs.recipies.scm.RepositoryInfo;
import com.redhat.hacbs.recipies.scm.ScmInfo;
import com.redhat.hacbs.recipies.scm.TagMapping;

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
        return RepositoryChange.createPullRequest(gav.replace(":", "-"), "Add scm-info for " + gav,
                (repositoryRoot, groupManager, recipeLayoutManager) -> {
                    GAV parsed = GAV.parse(gav);
                    var existing = groupManager.lookupScmInformation(parsed);
                    if (!legacy) {
                        ScmInfo scmInfo = null;
                        Path existingFile = null;
                        if (!existing.isEmpty()) {
                            existingFile = existing.get(0);
                            scmInfo = BuildRecipe.SCM.getHandler().parse(existingFile);
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
                        if (existingFile != null && uri == null) {
                            BuildRecipe.SCM.getHandler().write(scmInfo, existingFile);
                        } else {
                            recipeLayoutManager.writeArtifactData(new AddRecipeRequest<>(BuildRecipe.SCM, scmInfo,
                                    parsed.getGroupId(), group ? null : parsed.getArtifactId(),
                                    version ? parsed.getVersion() : null));
                        }

                    } else {
                        //legacy mode, we just want to add legacy info to an existing file
                        if (existing.isEmpty()) {
                            throw new RuntimeException("Cannot use --legacy when there is no existing data");
                        }
                        Path existingFile = existing.get(0);
                        ScmInfo existingInfo = BuildRecipe.SCM.getHandler().parse(existingFile);
                        RepositoryInfo repo = null;
                        for (var existingLegacy : existingInfo.getLegacyRepos()) {
                            if (existingLegacy.getUri().equals(uri)) {
                                repo = existingInfo;
                                break;
                            }
                        }
                        if (repo == null) {
                            existingInfo.getLegacyRepos().add(repo = new RepositoryInfo("git", uri, path));
                        }
                        if (tagMapping != null) {
                            if (repo.getTagMapping() == null) {
                                repo.setTagMapping(new ArrayList<>());
                            }
                            for (var e : tagMapping.entrySet()) {
                                repo.getTagMapping().add(new TagMapping().setPattern(e.getKey()).setTag(e.getValue()));
                            }
                        }
                        BuildRecipe.SCM.getHandler().write(existingInfo, existingFile);
                    }
                });
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

package com.redhat.hacbs.resources.model.v1alpha1;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ScmInfo {
    private String scmType;
    private String scmURL;
    private String tag;

    private String path;

    boolean privateRepo;

    public String getPath() {
        return path;
    }

    public ScmInfo setPath(String path) {
        this.path = path;
        return this;
    }

    public String getScmType() {
        return scmType;
    }

    public ScmInfo setScmType(String scmType) {
        this.scmType = scmType;
        return this;
    }

    public String getScmURL() {
        return scmURL;
    }

    public ScmInfo setScmURL(String scmURL) {
        this.scmURL = scmURL;
        return this;
    }

    public String getTag() {
        return tag;
    }

    public ScmInfo setTag(String tag) {
        this.tag = tag;
        return this;
    }

    @JsonProperty("private")
    public boolean isPrivateRepo() {
        return privateRepo;
    }

    public ScmInfo setPrivateRepo(boolean privateRepo) {
        this.privateRepo = privateRepo;
        return this;
    }
}

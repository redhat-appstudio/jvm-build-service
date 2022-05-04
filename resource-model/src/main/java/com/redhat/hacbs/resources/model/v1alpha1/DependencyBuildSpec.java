package com.redhat.hacbs.resources.model.v1alpha1;

public class DependencyBuildSpec {

    private String scmUrl;
    private String scmType;

    private String version;
    private String tag;

    public String getScmUrl() {
        return scmUrl;
    }

    public DependencyBuildSpec setScmUrl(String scmUrl) {
        this.scmUrl = scmUrl;
        return this;
    }

    public String getScmType() {
        return scmType;
    }

    public DependencyBuildSpec setScmType(String scmType) {
        this.scmType = scmType;
        return this;
    }

    public String getVersion() {
        return version;
    }

    public DependencyBuildSpec setVersion(String version) {
        this.version = version;
        return this;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getTag() {
        return tag;
    }
}

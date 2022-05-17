package com.redhat.hacbs.resources.model.v1alpha1;

public class DependencyBuildSpec {

    private String scmURL;
    private String scmType;

    private String path;

    private String version;
    private String tag;

    public String getScmURL() {
        return scmURL;
    }

    public DependencyBuildSpec setScmURL(String scmURL) {
        this.scmURL = scmURL;
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

    public String getPath() {
        return path;
    }

    public DependencyBuildSpec setPath(String path) {
        this.path = path;
        return this;
    }
}

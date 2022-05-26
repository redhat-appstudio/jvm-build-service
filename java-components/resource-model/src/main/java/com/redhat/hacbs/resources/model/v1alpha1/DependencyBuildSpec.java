package com.redhat.hacbs.resources.model.v1alpha1;

public class DependencyBuildSpec {

    private ScmInfo scm;

    private String version;

    public ScmInfo getScm() {
        return scm;
    }

    public DependencyBuildSpec setScm(ScmInfo scm) {
        this.scm = scm;
        return this;
    }

    public String getVersion() {
        return version;
    }

    public DependencyBuildSpec setVersion(String version) {
        this.version = version;
        return this;
    }
}

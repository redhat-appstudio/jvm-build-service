package com.redhat.hacbs.recipies.scm;

public class ScmInfo {

    String type;
    String uri;

    public ScmInfo() {
    }

    public ScmInfo(String type, String uri) {
        this.type = type;
        this.uri = uri;
    }

    public String getType() {
        return type;
    }

    public String getUri() {
        return uri;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }
}

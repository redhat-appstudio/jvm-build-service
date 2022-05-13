package com.redhat.hacbs.recipies.scm;

public class RepositoryInfo {

    String type;
    String uri;

    String path;

    public RepositoryInfo() {
    }

    public RepositoryInfo(String type, String uri) {
        this.type = type;
        this.uri = uri;
    }

    public RepositoryInfo(String type, String uri, String path) {
        this.type = type;
        this.uri = uri;
        this.path = path;
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

    public String getPath() {
        return path;
    }

    public RepositoryInfo setPath(String path) {
        this.path = path;
        return this;
    }
}

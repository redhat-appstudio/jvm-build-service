package com.redhat.hacbs.recipies.scm;

import java.util.ArrayList;
import java.util.List;

public class ScmInfo {

    String type;
    String uri;
    String path;

    private List<TagMapping> tagMapping = new ArrayList<>();

    public ScmInfo() {
    }

    public ScmInfo(String type, String uri) {
        this.type = type;
        this.uri = uri;
    }

    public ScmInfo(String type, String uri, String path) {
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

    public ScmInfo setPath(String path) {
        this.path = path;
        return this;
    }

    public List<TagMapping> getTagMapping() {
        return tagMapping;
    }

    public ScmInfo setTagMapping(List<TagMapping> tagMapping) {
        this.tagMapping = tagMapping;
        return this;
    }
}

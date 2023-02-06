package com.redhat.hacbs.recipies.scm;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RepositoryInfo {

    String type;
    String uri;
    String path;

    boolean privateRepo;

    private List<TagMapping> tagMapping = new ArrayList<>();

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

    public void setPath(String path) {
        this.path = path;
    }

    public List<TagMapping> getTagMapping() {
        return tagMapping;
    }

    public void setTagMapping(List<TagMapping> tagMapping) {
        this.tagMapping = tagMapping;
    }

    @JsonProperty("private")
    public boolean isPrivateRepo() {
        return privateRepo;
    }

    public RepositoryInfo setPrivateRepo(boolean privateRepo) {
        this.privateRepo = privateRepo;
        return this;
    }
}

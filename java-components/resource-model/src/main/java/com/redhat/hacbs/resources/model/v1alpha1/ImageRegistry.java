package com.redhat.hacbs.resources.model.v1alpha1;

public class ImageRegistry {
    private String host;
    private String port;
    private String owner;
    private String repository;
    private boolean insecure;
    private String prependTag;

    public String getHost() {
        return host;
    }

    public ImageRegistry setHost(String host) {
        this.host = host;
        return this;
    }

    public String getPort() {
        return port;
    }

    public ImageRegistry setPort(String port) {
        this.port = port;
        return this;
    }

    public String getOwner() {
        return owner;
    }

    public ImageRegistry setOwner(String owner) {
        this.owner = owner;
        return this;
    }

    public String getRepository() {
        return repository;
    }

    public ImageRegistry setRepository(String repository) {
        this.repository = repository;
        return this;
    }

    public boolean isInsecure() {
        return insecure;
    }

    public ImageRegistry setInsecure(boolean insecure) {
        this.insecure = insecure;
        return this;
    }

    public String getPrependTag() {
        return prependTag;
    }

    public ImageRegistry setPrependTag(String prependTag) {
        this.prependTag = prependTag;
        return this;
    }
}

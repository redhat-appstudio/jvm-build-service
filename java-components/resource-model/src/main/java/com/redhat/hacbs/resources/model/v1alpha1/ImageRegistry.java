package com.redhat.hacbs.resources.model.v1alpha1;

import java.util.Objects;

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

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ImageRegistry that = (ImageRegistry) o;
        return insecure == that.insecure && Objects.equals(host, that.host) && Objects.equals(port, that.port)
                && Objects.equals(owner, that.owner) && Objects.equals(repository, that.repository) && Objects.equals(
                        prependTag, that.prependTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, owner, repository, insecure, prependTag);
    }

    public static ImageRegistry parseRegistry(String registry) {
        ImageRegistry result = new ImageRegistry();
        // This represents a comma-separated sequence in the *same* order as defined in
        // ImageRegistry in pkg/apis/jvmbuildservice/v1alpha1/jbsconfig_types.go
        String[] splitRegistry = registry.split(",", -1);
        if (splitRegistry.length != 6) {
            throw new RuntimeException("Invalid registry format");
        }
        result.setHost(splitRegistry[0]);
        result.setPort(splitRegistry[1]);
        result.setOwner(splitRegistry[2]);
        result.setRepository(splitRegistry[3]);
        result.setInsecure(Boolean.parseBoolean(splitRegistry[4]));
        result.setPrependTag(splitRegistry[5]);

        return result;
    }

}

package com.redhat.hacbs.resources.model.v1alpha1;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JBSConfigSpec {
    private boolean enableRebuilds;

    // If this is true then the build will fail if artifact verification fails
    // otherwise deploy will happen as normal, but a field will be set on the DependencyBuild
    private boolean requireArtifactVerification;

    private List<String> additionalRecipes;

    private Map<String, String> mavenBaseLocations;
    private String host;
    private String port;
    private String owner;
    private String repository;
    private boolean insecure;
    private String prependTag;
    private CacheSettings cacheSettings;
    private BuildSettings buildSettings;

    public boolean isEnableRebuilds() {
        return enableRebuilds;
    }

    public JBSConfigSpec setEnableRebuilds(boolean enableRebuilds) {
        this.enableRebuilds = enableRebuilds;
        return this;
    }

    public boolean isRequireArtifactVerification() {
        return requireArtifactVerification;
    }

    public JBSConfigSpec setRequireArtifactVerification(boolean requireArtifactVerification) {
        this.requireArtifactVerification = requireArtifactVerification;
        return this;
    }

    public List<String> getAdditionalRecipes() {
        return additionalRecipes;
    }

    public JBSConfigSpec setAdditionalRecipes(List<String> additionalRecipes) {
        this.additionalRecipes = additionalRecipes;
        return this;
    }

    public Map<String, String> getMavenBaseLocations() {
        return mavenBaseLocations;
    }

    public JBSConfigSpec setMavenBaseLocations(Map<String, String> mavenBaseLocations) {
        this.mavenBaseLocations = mavenBaseLocations;
        return this;
    }

    public String getHost() {
        return host;
    }

    public JBSConfigSpec setHost(String host) {
        this.host = host;
        return this;
    }

    public String getPort() {
        return port;
    }

    public JBSConfigSpec setPort(String port) {
        this.port = port;
        return this;
    }

    public String getOwner() {
        return owner;
    }

    public JBSConfigSpec setOwner(String owner) {
        this.owner = owner;
        return this;
    }

    public String getRepository() {
        return repository;
    }

    public JBSConfigSpec setRepository(String repository) {
        this.repository = repository;
        return this;
    }

    public boolean isInsecure() {
        return insecure;
    }

    public JBSConfigSpec setInsecure(boolean insecure) {
        this.insecure = insecure;
        return this;
    }

    public String getPrependTag() {
        return prependTag;
    }

    public JBSConfigSpec setPrependTag(String prependTag) {
        this.prependTag = prependTag;
        return this;
    }

    public CacheSettings getCacheSettings() {
        return cacheSettings;
    }

    public JBSConfigSpec setCacheSettings(CacheSettings cacheSettings) {
        this.cacheSettings = cacheSettings;
        return this;
    }

    public BuildSettings getBuildSettings() {
        return buildSettings;
    }

    public JBSConfigSpec setBuildSettings(BuildSettings buildSettings) {
        this.buildSettings = buildSettings;
        return this;
    }
}

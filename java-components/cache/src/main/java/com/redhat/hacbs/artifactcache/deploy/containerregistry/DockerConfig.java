package com.redhat.hacbs.artifactcache.deploy.containerregistry;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DockerConfig {

    private Map<String, DockerAuthEntry> auths;

    public Map<String, DockerAuthEntry> getAuths() {
        return auths;
    }

    public DockerConfig setAuths(Map<String, DockerAuthEntry> auths) {
        this.auths = auths;
        return this;
    }
}

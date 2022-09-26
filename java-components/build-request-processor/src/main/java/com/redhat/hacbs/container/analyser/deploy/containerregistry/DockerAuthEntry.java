package com.redhat.hacbs.container.analyser.deploy.containerregistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DockerAuthEntry {

    private String auth;

    public String getAuth() {
        return auth;
    }

    public DockerAuthEntry setAuth(String auth) {
        this.auth = auth;
        return this;
    }
}

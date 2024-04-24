package com.redhat.hacbs.common.images.ociclient;

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DockerAuthEntry {

        private String auth;

        public String getAuth() {
            return auth;
        }

        public DockerAuthEntry setAuth(String auth) {
            this.auth = auth;
            return this;
        }
    }

}

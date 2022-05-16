package com.redhat.hacbs.analyser.maven;

import java.util.Map;

import com.redhat.hacbs.recipies.GAV;

public class MavenProject {
    private MavenModule topLevel;
    private final Map<GAV, MavenModule> projects;
    String path;

    public MavenProject(Map<GAV, MavenModule> projects) {
        this.projects = projects;
    }

    public Map<GAV, MavenModule> getProjects() {
        return projects;
    }

    public String getPath() {
        return path;
    }

    public MavenProject setPath(String path) {
        this.path = path;
        return this;
    }
}

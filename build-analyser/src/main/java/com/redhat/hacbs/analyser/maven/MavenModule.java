package com.redhat.hacbs.analyser.maven;

import java.nio.file.Path;

import com.redhat.hacbs.analyser.GAV;

public class MavenModule {

    private final GAV gav;
    private final GAV parent;
    private final Path pomFile;

    public MavenModule(GAV gav, GAV parent, Path pomFile) {
        this.gav = gav;
        this.parent = parent;
        this.pomFile = pomFile;
    }

    public GAV getGav() {
        return gav;
    }

    public GAV getParent() {
        return parent;
    }

    public Path getPomFile() {
        return pomFile;
    }
}

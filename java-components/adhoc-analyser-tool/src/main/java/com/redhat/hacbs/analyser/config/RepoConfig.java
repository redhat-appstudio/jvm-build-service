package com.redhat.hacbs.analyser.config;

import java.nio.file.Path;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "repo")
public interface RepoConfig {

    Path path();

}

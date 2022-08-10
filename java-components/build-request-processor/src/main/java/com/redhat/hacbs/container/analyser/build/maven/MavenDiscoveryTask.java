package com.redhat.hacbs.container.analyser.build.maven;

import java.nio.file.Path;

import org.apache.maven.model.Model;

import com.redhat.hacbs.container.analyser.build.DiscoveryResult;

public interface MavenDiscoveryTask {

    DiscoveryResult discover(Model model, Path checkout);

}

package com.redhat.hacbs.driver.util;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.pnc.api.dto.ComponentVersion;

import io.quarkus.info.BuildInfo;
import io.quarkus.info.GitInfo;

@RequestScoped
public class Info {

    @ConfigProperty(name = "quarkus.application.name")
    String name;

    @Inject
    GitInfo gitInfo;

    @Inject
    BuildInfo buildInfo;

    public ComponentVersion getVersion() {
        return ComponentVersion.builder()
                .name(name)
                .builtOn(buildInfo.time().toZonedDateTime())
                .commit(gitInfo.latestCommitId())
                .version(buildInfo.version())
                .build();
    }

}

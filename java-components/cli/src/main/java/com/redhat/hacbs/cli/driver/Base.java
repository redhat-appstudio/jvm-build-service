package com.redhat.hacbs.cli.driver;

import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import picocli.CommandLine;

public abstract class Base {
    @CommandLine.Option(names = "-n", description = "Namespace", defaultValue = "pnc-devel-tenant")
    String namespace;

    @CommandLine.Option(names = "-u", description = "URL", required = true)
    String url;

    @CommandLine.Option(names = "-r", description = "Revision", required = true)
    String revision;

    @CommandLine.Option(names = "-t", description = "Build Tool", required = true)
    String buildTool;

    @CommandLine.Option(names = "--tool-version", description = "Tool Version", required = true)
    String buildToolVersion;

    @CommandLine.Option(names = "-j", description = "Java Version", required = true)
    String javaVersion;

    @CommandLine.Option(names = "-s", description = "Build Script", required = true)
    String buildScript;

    @CommandLine.Option(names = "-d", description = "Deploy URL", required = true)
    String deploy;

    @ConfigProperty(name = "access.token")
    Optional<String> accessToken;

}

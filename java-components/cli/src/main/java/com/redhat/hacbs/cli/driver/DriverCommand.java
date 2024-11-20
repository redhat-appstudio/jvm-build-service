package com.redhat.hacbs.cli.driver;

import picocli.CommandLine;

@CommandLine.Command(name = "driver", subcommands = {
        Fabric8.class, Pipeline.class }, mixinStandardHelpOptions = true)
public class DriverCommand {
}

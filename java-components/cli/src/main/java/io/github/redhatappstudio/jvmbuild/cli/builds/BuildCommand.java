package io.github.redhatappstudio.jvmbuild.cli.builds;

import picocli.CommandLine;

@CommandLine.Command(name = "build", subcommands = { BuildLogsCommand.class, BuildSummaryCommand.class,
        BuildListCommand.class })
public class BuildCommand {
}

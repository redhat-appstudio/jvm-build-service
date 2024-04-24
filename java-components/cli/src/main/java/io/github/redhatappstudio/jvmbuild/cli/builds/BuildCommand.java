package io.github.redhatappstudio.jvmbuild.cli.builds;

import picocli.CommandLine;

@CommandLine.Command(name = "build", subcommands = {
        BuildLogsCommand.class,
        BuildCreateCommand.class,
        BuildSummaryCommand.class,
        BuildFixCommand.class,
        BuildDiagnosticCommand.class,
        BuildListCommand.class }, mixinStandardHelpOptions = true)
public class BuildCommand {
}

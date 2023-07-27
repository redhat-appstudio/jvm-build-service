package io.github.redhatappstudio.jvmbuild.cli.settings;

import picocli.CommandLine;

@CommandLine.Command(name = "setup", subcommands = {
        SetupRebuildsCommand.class, SetupSharedRepositoriesCommand.class }, mixinStandardHelpOptions = true)
public class SetupCommand {
}

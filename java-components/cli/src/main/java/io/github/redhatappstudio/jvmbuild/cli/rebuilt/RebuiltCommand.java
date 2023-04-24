package io.github.redhatappstudio.jvmbuild.cli.rebuilt;

import picocli.CommandLine;

@CommandLine.Command(name = "rebuilt", subcommands = {
        RebuiltListCommand.class, RebuiltDownloadCommand.class }, mixinStandardHelpOptions = true)
public class RebuiltCommand {
}

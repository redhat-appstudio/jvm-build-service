package com.redhat.hacbs.analyser.pnc;

import picocli.CommandLine;

@CommandLine.Command(name = "pnc", subcommands = { PncRepositoryListCommand.class })
public class PncCommand {
}

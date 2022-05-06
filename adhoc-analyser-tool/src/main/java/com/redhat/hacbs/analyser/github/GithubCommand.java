package com.redhat.hacbs.analyser.github;

import picocli.CommandLine;

@CommandLine.Command(name = "github", subcommands = { GithubAnalyseReposCommand.class })
public class GithubCommand {
}

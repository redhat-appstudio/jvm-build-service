package com.redhat.hacbs.container.analyser.dependencies;

import picocli.CommandLine;

@CommandLine.Command(name = "analyse-dependencies", subcommands = { AnalyseImage.class, AnalysePath.class })
public class AnalyseDependencies {
}

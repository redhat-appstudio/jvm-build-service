package com.redhat.hacbs.container.analyser;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand()
@CommandLine.Command(subcommands = { AnalyseImage.class, AnalysePath.class })
public class Main {
}

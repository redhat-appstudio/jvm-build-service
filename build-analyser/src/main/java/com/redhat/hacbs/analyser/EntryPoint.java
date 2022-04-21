package com.redhat.hacbs.analyser;

import com.redhat.hacbs.analyser.artifactanalysis.AnalyseRepositoriesCommand;
import com.redhat.hacbs.analyser.pnc.PncCommand;
import com.redhat.hacbs.analyser.pnc.PncRepositoryListCommand;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(mixinStandardHelpOptions = true, subcommands = { PncCommand.class, AnalyseRepositoriesCommand.class})
public class EntryPoint {
}

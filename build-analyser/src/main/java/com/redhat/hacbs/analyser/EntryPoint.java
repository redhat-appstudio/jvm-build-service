package com.redhat.hacbs.analyser;

import com.redhat.hacbs.analyser.artifactanalysis.AnalyseRepositoriesCommand;
import com.redhat.hacbs.analyser.artifactanalysis.ConsolidateScmInfoCommand;
import com.redhat.hacbs.analyser.pnc.PncCommand;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(mixinStandardHelpOptions = true, subcommands = { PncCommand.class, AnalyseRepositoriesCommand.class,
        ConsolidateScmInfoCommand.class })
public class EntryPoint {
}

package com.redhat.hacbs.analyser;

import com.redhat.hacbs.analyser.artifactanalysis.AnalyseRepositoriesCommand;
import com.redhat.hacbs.analyser.artifactanalysis.CheckoutRepositoriesCommand;
import com.redhat.hacbs.analyser.artifactanalysis.ConsolidateScmInfoCommand;
import com.redhat.hacbs.analyser.github.GithubCommand;
import com.redhat.hacbs.analyser.kube.ListBuildRequestsCommand;
import com.redhat.hacbs.analyser.kube.ResetDependencyBuildsCommand;
import com.redhat.hacbs.analyser.pnc.PncCommand;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(mixinStandardHelpOptions = true, subcommands = { PncCommand.class, AnalyseRepositoriesCommand.class,
        ConsolidateScmInfoCommand.class, GithubCommand.class, CheckoutRepositoriesCommand.class, ListBuildRequestsCommand.class,
        ResetDependencyBuildsCommand.class
})
public class EntryPoint {
}

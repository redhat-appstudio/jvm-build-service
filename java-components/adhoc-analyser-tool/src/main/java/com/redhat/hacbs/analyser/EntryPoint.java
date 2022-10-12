package com.redhat.hacbs.analyser;

import com.redhat.hacbs.analyser.artifactanalysis.LegacyAnalyseRepositoriesCommand;
import com.redhat.hacbs.analyser.artifactanalysis.LegacyCheckoutRepositoriesCommand;
import com.redhat.hacbs.analyser.artifactanalysis.ConsolidateScmInfoCommand;
import com.redhat.hacbs.analyser.github.GithubCommand;
import com.redhat.hacbs.analyser.kube.ListBuildRequestsCommand;
import com.redhat.hacbs.analyser.kube.ResetArtifactBuildsCommand;
import com.redhat.hacbs.analyser.kube.ResetDependencyBuildsCommand;
import com.redhat.hacbs.analyser.pnc.PncCommand;

import com.redhat.hacbs.analyser.repoutils.AnalyseRepositoryCommand;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(mixinStandardHelpOptions = true, subcommands = { PncCommand.class, LegacyAnalyseRepositoriesCommand.class,
        ConsolidateScmInfoCommand.class, GithubCommand.class, LegacyCheckoutRepositoriesCommand.class, ListBuildRequestsCommand.class,
        ResetDependencyBuildsCommand.class, ResetArtifactBuildsCommand.class, AnalyseRepositoryCommand.class
})
public class EntryPoint {
}

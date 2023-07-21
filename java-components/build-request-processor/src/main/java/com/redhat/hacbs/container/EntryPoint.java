package com.redhat.hacbs.container;

import com.redhat.hacbs.container.analyser.build.LookupBuildInfoCommand;
import com.redhat.hacbs.container.analyser.dependencies.AnalyseDependencies;
import com.redhat.hacbs.container.analyser.deploy.ContainerTagCommand;
import com.redhat.hacbs.container.analyser.deploy.DeployCommand;
import com.redhat.hacbs.container.analyser.deploy.DeployPreBuildImageCommand;
import com.redhat.hacbs.container.analyser.location.LookupScmLocationCommand;
import com.redhat.hacbs.container.build.preprocessor.ant.AntPrepareCommand;
import com.redhat.hacbs.container.build.preprocessor.gradle.GradlePrepareCommand;
import com.redhat.hacbs.container.build.preprocessor.maven.MavenPrepareCommand;
import com.redhat.hacbs.container.build.preprocessor.sbt.SBTPrepareCommand;
import com.redhat.hacbs.container.verifier.VerifyBuiltArtifactsCommand;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(mixinStandardHelpOptions = true, subcommands = {
        LookupScmLocationCommand.class,
        LookupBuildInfoCommand.class,
        AnalyseDependencies.class,
        DeployCommand.class,
        GradlePrepareCommand.class,
        MavenPrepareCommand.class,
        VerifyBuiltArtifactsCommand.class,
        SBTPrepareCommand.class,
        AntPrepareCommand.class,
        DeployPreBuildImageCommand.class,
        ContainerTagCommand.class
})
public class EntryPoint {
}

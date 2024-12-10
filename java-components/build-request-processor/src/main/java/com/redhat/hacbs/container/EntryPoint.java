package com.redhat.hacbs.container;

import com.redhat.hacbs.container.analyser.build.LookupBuildInfoCommand;
import com.redhat.hacbs.container.analyser.dependencies.AnalyseDependencies;
import com.redhat.hacbs.container.analyser.location.LookupScmLocationCommand;
import com.redhat.hacbs.container.build.preprocessor.ant.AntPrepareCommand;
import com.redhat.hacbs.container.build.preprocessor.gradle.GradlePrepareCommand;
import com.redhat.hacbs.container.build.preprocessor.maven.MavenPrepareCommand;
import com.redhat.hacbs.container.build.preprocessor.sbt.SBTPrepareCommand;
import com.redhat.hacbs.container.deploy.BuildVerifyCommand;
import com.redhat.hacbs.container.deploy.CopyArtifactsCommand;
import com.redhat.hacbs.container.deploy.DeployCommand;
import com.redhat.hacbs.container.deploy.DeployPreBuildSourceCommand;
import com.redhat.hacbs.container.verifier.VerifyBuiltArtifactsCommand;
import com.redhat.hacbs.container.notification.NotifyCommand;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(mixinStandardHelpOptions = true, subcommands = {
        AnalyseDependencies.class,
        AntPrepareCommand.class,
        CopyArtifactsCommand.class,
        BuildVerifyCommand.class,
        GradlePrepareCommand.class,
        DeployPreBuildSourceCommand.class,
        LookupBuildInfoCommand.class,
        LookupScmLocationCommand.class,
        DeployCommand.class,
        NotifyCommand.class,
        MavenPrepareCommand.class,
        SBTPrepareCommand.class,
        VerifyBuiltArtifactsCommand.class
})
public class EntryPoint {
}

package com.redhat.hacbs.container.analyser.deploy;

import java.nio.file.Path;
import java.util.Set;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import com.redhat.hacbs.container.analyser.deploy.s3.S3Deployer;
import com.redhat.hacbs.container.results.ResultsUpdater;

import picocli.CommandLine;
import software.amazon.awssdk.services.s3.S3Client;

@CommandLine.Command(name = "deploy-s3")
public class S3DeployCommand extends DeployCommand {

    @CommandLine.Option(names = "--deployment-bucket", defaultValue = "build-artifacts")
    String deploymentBucket;

    @CommandLine.Option(names = "--deployment-prefix", defaultValue = "")
    String prefix;

    @Inject
    S3Client s3Client;

    @Inject
    public S3DeployCommand(BeanManager beanManager, ResultsUpdater resultsUpdater) {
        super(beanManager, resultsUpdater);
    }

    @Override
    protected void doDeployment(Path deployFile, Path sourcePath, Path logsPath, Set<String> gavs) throws Exception {
        S3Deployer deployer = new S3Deployer(s3Client, deploymentBucket, prefix);
        deployer.deployArchive(deployFile, sourcePath, logsPath, gavs);
    }
}

package com.redhat.hacbs.cli.driver;

import java.io.IOException;

import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.hacbs.driver.Driver;
import com.redhat.hacbs.driver.dto.BuildRequest;

import picocli.CommandLine;

@CommandLine.Command(name = "pipeline", mixinStandardHelpOptions = true, description = "Creates a pipeline")
public class Pipeline extends Base implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(Pipeline.class);

    @Inject
    Driver driver;

    @ActivateRequestContext // https://github.com/quarkusio/quarkus/issues/8758
    @Override
    public void run() {
        logger.info("### in here with driver {}", driver);
        driver.addAccessToken(accessToken.orElse("NO_TOKEN"));

        BuildRequest request = BuildRequest.builder()
                .namespace(namespace)
                .scmUrl(url)
                .scmRevision(revision)
                .buildTool(buildTool)
                .buildToolVersion(buildToolVersion)
                .javaVersion(javaVersion)
                .buildScript(buildScript)
                .repositoryDeployUrl(deploy)
                .repositoryDependencyUrl(deploy)
                .repositoryBuildContentId("test-maven-konflux-int-0001")
                .build();
        try {
            driver.create(request);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
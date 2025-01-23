package com.redhat.hacbs.cli.driver;

import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import org.jboss.pnc.api.konfluxbuilddriver.dto.BuildRequest;
import org.jboss.pnc.api.konfluxbuilddriver.dto.BuildResponse;
import org.jboss.pnc.konfluxbuilddriver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@CommandLine.Command(name = "create-pipeline", mixinStandardHelpOptions = true, description = "Creates a pipeline")
public class CreatePipeline extends Base implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CreatePipeline.class);

    @Inject
    Driver driver;

    @CommandLine.Option(names = "-i", description = "Recipe Image", defaultValue = "quay.io/redhat-user-workloads/konflux-jbs-pnc-tenant/jvm-build-service-builder-images/ubi8:latest")
    String recipeImage;

    // Not possible to set Quarkus configuration via CLI due to
    // https://github.com/quarkusio/quarkus/issues/19128
    // Therefore create an application.properties file in the directory where the CLI is run
    // and configure the values there manually.
    //
    //    @CommandLine.Option(names = "--quay", description = "Quay repo", defaultValue = "quay.io/redhat-user-workloads-stage/pnc-devel-tenant/pnc-konflux")
    //    String quayRepo;
    //
    //    @CommandLine.Option(names = "--processor", description = "Request Process Image", defaultValue = "quay.io/redhat-user-workloads/konflux-jbs-pnc-tenant/jvm-build-service/build-request-processor:latest")
    //    String processor;

    @ActivateRequestContext // https://github.com/quarkusio/quarkus/issues/8758
    @Override
    public void run() {
        //        driver.setQuayRepo(quayRepo);
        //        driver.setProcessor(processor);
        //        driver.setAccessToken(accessToken.orElse(""));

        BuildRequest request = BuildRequest.builder()
                .namespace(namespace)
                .scmUrl(url)
                .scmRevision(revision)
                .buildTool(buildTool)
                .buildToolVersion(buildToolVersion)
                .javaVersion(javaVersion)
                .buildScript(buildScript)
                .repositoryDeployUrl(deploy)
                .repositoryDependencyUrl(dependencies)
                .repositoryBuildContentId("test-maven-konflux-int-0001")
                .recipeImage(recipeImage)
                // Just use default from buildah-oci-ta for now.
                .podMemoryOverride("4Gi")
                .build();
        BuildResponse b = driver.create(request);

        logger.info("Got response {}", b);
    }
}

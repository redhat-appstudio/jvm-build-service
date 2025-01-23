package com.redhat.hacbs.cli.driver;

import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import org.jboss.pnc.api.konfluxbuilddriver.dto.CancelRequest;
import org.jboss.pnc.konfluxbuilddriver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;

@CommandLine.Command(name = "cancel-pipeline", mixinStandardHelpOptions = true, description = "Creates a pipeline")
public class CancelPipeline implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(CancelPipeline.class);

    @Inject
    Driver driver;

    @CommandLine.Option(names = "-n", description = "Namespace", defaultValue = "pnc-devel-tenant")
    String namespace;

    @CommandLine.Option(names = "-p", description = "Pipeline name")
    String pipeline;

    @ActivateRequestContext // https://github.com/quarkusio/quarkus/issues/8758
    @Override
    public void run() {
        var cancel = CancelRequest.builder()
                .namespace(namespace)
                .pipelineId(pipeline)
                .build();

        driver.cancel(cancel);
    }
}

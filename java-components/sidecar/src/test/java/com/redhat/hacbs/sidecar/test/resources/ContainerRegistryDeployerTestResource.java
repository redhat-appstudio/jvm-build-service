package com.redhat.hacbs.sidecar.test.resources;

import java.util.Map;

import org.testcontainers.containers.GenericContainer;

import io.quarkus.logging.Log;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class ContainerRegistryDeployerTestResource implements QuarkusTestResourceLifecycleManager {

    GenericContainer container;

    @Override
    public Map<String, String> start() {

        int port = startTestRegistry();

        return Map.of(
                "deployer", "ContainerRegistryDeployer",
                "containerregistrydeployer.host", this.container.getHost(),
                "containerregistrydeployer.port", String.valueOf(port),
                "containerregistrydeployer.repository", REPOSITORY,
                "containerregistrydeployer.owner", OWNER,
                "containerregistrydeployer.insecure", "true");
    }

    private int startTestRegistry() {
        this.container = new GenericContainer("registry:2.8.1")
                .withReuse(true)
                .withExposedPorts(5000)
                .withEnv("TESTCONTAINERS_RYUK_DISABLED", "true");

        this.container.start();

        Integer port = this.container.getMappedPort(5000);

        Log.info("\n Test registry details:\n"
                + "\t container name: " + this.container.getContainerName() + "\n"
                + "\t host: " + this.container.getHost() + "\n"
                + "\t port: " + port + "\n"
                + "\t image: " + this.container.getDockerImageName() + "\n");

        return port;
    }

    @Override
    public void stop() {
        this.container.stop();
    }

    private static final String REPOSITORY = "artifact-deployments";
    private static final String OWNER = "hacbs-test";
}

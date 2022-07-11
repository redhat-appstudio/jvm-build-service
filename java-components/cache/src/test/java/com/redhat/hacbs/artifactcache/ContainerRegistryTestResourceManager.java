package com.redhat.hacbs.artifactcache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.testcontainers.containers.GenericContainer;

import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.ImageFormat;
import com.redhat.hacbs.artifactcache.services.ContainerRegistryStorageTest;
import com.redhat.hacbs.artifactcache.services.client.ShaUtil;

import io.quarkus.logging.Log;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.json.JsonObject;

public class ContainerRegistryTestResourceManager implements QuarkusTestResourceLifecycleManager {

    GenericContainer container;

    @Override
    public Map<String, String> start() {
        try {
            int port = startTestRegistry();
            createTestData(this.container.getHost(), port);
            printTestRegistry(this.container.getHost(), port);

            return Map.of(
                    "store.rebuilt.type", "oci_registry",
                    "store.rebuilt.registry", this.container.getHost() + ":" + String.valueOf(port),
                    "store.rebuilt.owner", OWNER,
                    "store.rebuilt.insecure", "true");
        } catch (IOException | InvalidImageReferenceException | InterruptedException | RegistryException
                | CacheDirectoryCreationException | ExecutionException ex) {
            throw new RuntimeException("Problem while starting test registry", ex);
        }
    }

    private int startTestRegistry() {
        this.container = new GenericContainer("registry:2.7")
                .withReuse(true)
                .withExposedPorts(5000);

        this.container.start();

        Integer port = this.container.getMappedPort(5000);

        Log.info("\n Test registry details:\n"
                + "\t container name: " + this.container.getContainerName() + "\n"
                + "\t host: " + this.container.getHost() + "\n"
                + "\t port: " + port + "\n"
                + "\t image: " + this.container.getDockerImageName() + "\n");

        return port;
    }

    private void createTestData(String host, int port) throws IOException, InvalidImageReferenceException, InterruptedException,
            RegistryException, CacheDirectoryCreationException, ExecutionException {

        Path testDataRoot = Paths.get("src/test/data/").toAbsolutePath();
        Path layer1Path = testDataRoot.resolve("source");
        Path layer2Path = testDataRoot.resolve("logs");
        Path layer3Path = testDataRoot.resolve("artifacts");

        Log.info("\n Test container details:\n"
                + "\t layer 1 (source) " + layer1Path.toString() + "\n"
                + "\t layer 2 (logs) " + layer2Path.toString() + "\n"
                + "\t layer 3 (artifacts) " + layer3Path.toString());

        Containerizer containerizer = Containerizer.to(RegistryImage.named(host + ":" + port + "/" + OWNER + "/"
                + ContainerRegistryStorageTest.GROUP + ":"
                + ContainerRegistryStorageTest.VERSION))
                .setAllowInsecureRegistries(true);

        for (String artifact : ContainerRegistryStorageTest.ARTIFACT_FILE_MAP.keySet()) {
            String tag = ShaUtil.sha256sum(ContainerRegistryStorageTest.GROUP, artifact,
                    ContainerRegistryStorageTest.VERSION);
            containerizer = containerizer.withAdditionalTag(tag);
        }

        AbsoluteUnixPath imageRoot = AbsoluteUnixPath.get("/");
        Jib.fromScratch()
                .setFormat(ImageFormat.OCI)
                .addLabel("groupId", ContainerRegistryStorageTest.GROUP)
                .addLabel("artifactId", "quarkus-parent")
                .addLabel("version", ContainerRegistryStorageTest.VERSION)
                .addLabel("description", "Quarkus")
                .addLayer(List.of(layer1Path), imageRoot)
                .addLayer(List.of(layer2Path), imageRoot)
                .addLayer(List.of(layer3Path), imageRoot)
                .containerize(containerizer);

    }

    private void printTestRegistry(String host, int port) throws MalformedURLException, IOException {

        // Print all the known registries
        StringBuilder resultCatalog = new StringBuilder();
        URL urlCatalog = new URL("http://" + host + ":" + port + "/v2/_catalog");
        HttpURLConnection connCatalog = (HttpURLConnection) urlCatalog.openConnection();
        connCatalog.setRequestMethod("GET");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connCatalog.getInputStream()))) {
            for (String line; (line = reader.readLine()) != null;) {
                resultCatalog.append(line);
            }
        }

        JsonObject catalogs = new JsonObject(resultCatalog.toString());
        Log.info("\n\n Test registry catalog:\n" + catalogs.encodePrettily() + "\n");

        // Print all the tags
        StringBuilder resultTags = new StringBuilder();
        URL urlTags = new URL(
                "http://" + host + ":" + port + "/v2/" + OWNER + "/" + ContainerRegistryStorageTest.GROUP + "/tags/list");
        HttpURLConnection conn = (HttpURLConnection) urlTags.openConnection();
        conn.setRequestMethod("GET");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            for (String line; (line = reader.readLine()) != null;) {
                resultTags.append(line);
            }
        }

        JsonObject tags = new JsonObject(resultTags.toString());
        Log.info("\n\n Test registry " + ContainerRegistryStorageTest.GROUP + " tags:\n" + tags.encodePrettily() + "\n");

        conn.disconnect();
    }

    @Override
    public void stop() {
        this.container.stop();
    }

    private static final String OWNER = "hacbs";

}

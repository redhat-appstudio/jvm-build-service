package com.redhat.hacbs.artifactcache;

import static com.redhat.hacbs.artifactcache.services.ContainerRegistryStorageTest.GROUP;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
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
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.ImageFormat;
import com.redhat.hacbs.artifactcache.services.ContainerRegistryStorageTest;
import com.redhat.hacbs.artifactcache.services.client.ShaUtil;
import com.redhat.hacbs.artifactcache.test.util.HashUtil;

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

        Log.debug("\n Test registry details:\n"
                + "\t container name: " + this.container.getContainerName() + "\n"
                + "\t host: " + this.container.getHost() + "\n"
                + "\t port: " + port + "\n"
                + "\t image: " + this.container.getDockerImageName() + "\n");

        return port;
    }

    private void createTestData(String host, int port) throws IOException, InvalidImageReferenceException, InterruptedException,
            RegistryException, CacheDirectoryCreationException, ExecutionException {

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
        JibContainerBuilder containerBuilder = Jib.fromScratch()
                .setFormat(ImageFormat.OCI)
                .addLabel("groupId", ContainerRegistryStorageTest.GROUP)
                .addLabel("artifactId", "foo-parent")
                .addLabel("version", ContainerRegistryStorageTest.VERSION)
                .addLabel("description", "Foo");

        List<Path> testLayers = createTestLayers();
        for (Path layer : testLayers) {
            containerBuilder = containerBuilder.addLayer(List.of(layer), imageRoot);
        }

        containerBuilder.containerize(containerizer);
    }

    private List<Path> createTestLayers() throws IOException {
        Path testDataRoot = Paths.get("target/test-data/").toAbsolutePath();
        Files.createDirectories(testDataRoot);

        Path layer1Path = Paths.get(testDataRoot.toString(), "source");
        Files.createDirectories(layer1Path);
        Path layer2Path = Paths.get(testDataRoot.toString(), "logs");
        Files.createDirectories(layer2Path);
        Path layer3Path = Paths.get(testDataRoot.toString(), "artifacts");
        Files.createDirectories(layer3Path);

        Log.debug("\n Test container details:\n"
                + "\t layer 1 (source) " + layer1Path.toString() + "\n"
                + "\t layer 2 (logs) " + layer2Path.toString() + "\n"
                + "\t layer 3 (artifacts) " + layer3Path.toString());

        // Add data to artifacts
        for (Map.Entry<String, String> artifactFile : ContainerRegistryStorageTest.ARTIFACT_FILE_MAP.entrySet()) {
            String groupPath = GROUP.replace(ContainerRegistryStorageTest.DOT, File.separator);
            Path testDir = Paths.get(layer3Path.toString(), groupPath, artifactFile.getKey(),
                    ContainerRegistryStorageTest.VERSION);
            Files.createDirectories(testDir);
            Path testFile = Paths.get(testDir.toString(), artifactFile.getValue());

            if (!Files.exists(testFile)) {
                Files.createFile(testFile);
            }
            String testContent = "Just some data for " + artifactFile.getKey();
            Files.writeString(testFile, testContent);

            Path shaFile = Paths.get(testFile.toString() + ContainerRegistryStorageTest.DOT + SHA_1);
            if (!Files.exists(shaFile)) {
                Files.createFile(shaFile);
            }

            String sha1 = HashUtil.sha1(testContent);
            Files.writeString(shaFile, sha1);
        }

        return List.of(layer1Path, layer2Path, layer3Path);
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
        Log.debug("\n\n Test registry catalog:\n" + catalogs.encodePrettily() + "\n");

        connCatalog.disconnect();

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
        Log.debug("\n\n Test registry " + ContainerRegistryStorageTest.GROUP + " tags:\n" + tags.encodePrettily() + "\n");

        conn.disconnect();
    }

    @Override
    public void stop() {
        this.container.stop();
    }

    private static final String OWNER = "hacbs";
    private static final String SHA_1 = "sha1";
}

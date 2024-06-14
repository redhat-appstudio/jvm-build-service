package com.redhat.hacbs.container.deploy;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import com.redhat.hacbs.resources.util.HashUtil;

import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@QuarkusMainTest
public class ContainerRegistryDeployerTest {
    private static final String REPOSITORY = "artifact-deployments";
    private static final String OWNER = "hacbs-test";

    private static final String GROUP = "com.company.foo";
    private static final String VERSION = "3.25.8";
    public static final String FOO_BAR = "foo-bar";
    public static final String FOO_BAZ = "foo-baz";
    private static final Map<String, String> ARTIFACT_FILE_MAP = Map.of(
            FOO_BAR, "foobar-" + VERSION + ".jar",
            FOO_BAZ, "foobaz-" + VERSION + ".jar");
    private static final String DOT = ".";
    private static final String SHA_1 = "sha1";

    private static final String EXPECTED_TAG_1 = "ddb962f47b1e1b33a3c59ce0e5a4a403c4e01373eca7fc6e992281f0c6324cc2";
    private static final String EXPECTED_TAG_2 = "f697132e6cc3e55c91e070ac80eabe7ff4a79ac336323fdadfaa191876d2b0d0";
    public static final String COMMIT = "3cf2d99b47f0a05466d1d0a2e09d8740faeda149";
    public static final String REPO = "https://github.com/foo/bar";

    static GenericContainer container;
    static int port;

    @BeforeAll
    public static void start() {
        port = startTestRegistry();
    }

    private static int startTestRegistry() {
        container = new GenericContainer("registry:2.8.1")
                .withReuse(true)
                .withExposedPorts(5000);

        container.start();

        return container.getMappedPort(5000);
    }

    @AfterAll
    public static void stop() {
        container.stop();
    }

    @Test
    public void testDeployArchive(QuarkusMainLauncher launcher) throws IOException {

        // Here we just make sure we can create images.
        Path onDiskRepo = createDeploymentRepo();
        Path source = Files.createTempDirectory("hacbs");
        Files.writeString(source.resolve("pom.xml"), "");
        Path logs = Files.createTempDirectory("hacbs");
        Files.writeString(logs.resolve("maven.log"), "");
        var result = launcher.launch("deploy", "--path=" + onDiskRepo.toAbsolutePath(),
                "--image-id=test-image",
//                "--registry-host=" + container.getHost(),
//                "--registry-port=" + port,
                "--build-id=test-id",
//                "--registry-owner=" + OWNER,
//                "--registry-repository=" + REPOSITORY,
                "--source-path=" + source.toAbsolutePath().toString(),
                "--logs-path=" + logs.toAbsolutePath().toString(),
                "--scm-uri=" + REPO,
                "--scm-commit=" + COMMIT
//                "--registry-insecure"
        );

        Assertions.assertEquals(0, result.exitCode());
//        // Now we validate that the image and tags exist in the registry
//        ContainerRegistryDetails containerRegistryDetails = getContainerRegistryDetails();
//        Assertions.assertTrue(containerRegistryDetails.repoName.startsWith(OWNER + "/" + REPOSITORY));
//        Assertions.assertTrue(containerRegistryDetails.tags.contains("test-image"));
//        Assertions.assertTrue(containerRegistryDetails.tags.contains("test-id"));
//        Assertions.assertFalse(containerRegistryDetails.tags.contains(EXPECTED_TAG_1));
//        Assertions.assertFalse(containerRegistryDetails.tags.contains(EXPECTED_TAG_2));
        System.out.println(result.getOutput());
//        Pattern p = Pattern.compile(DeployCommand.IMAGE_DIGEST_OUTPUT + "(.*)");
//        Matcher matcher = p.matcher(result.getOutput());
//        Assertions.assertTrue(matcher.find());
//        String digest = matcher.group(1);

//        result = launcher.launch("tag-container",
//                "--registry-host=" + container.getHost(),
//                "--registry-port=" + port,
//                "--registry-owner=" + OWNER,
//                "--registry-repository=" + REPOSITORY,
//                "--registry-insecure",
//                "--image-digest=" + digest,
//                GROUP + ":" + FOO_BAR + ":" + VERSION + "," + GROUP + ":" + FOO_BAZ + ":" + VERSION);
//        containerRegistryDetails = getContainerRegistryDetails();
//        Assertions.assertTrue(containerRegistryDetails.tags.contains(EXPECTED_TAG_1));
//        Assertions.assertTrue(containerRegistryDetails.tags.contains(EXPECTED_TAG_2));
    }

    private Path createDeploymentRepo() throws IOException {
        Path testData = Files.createTempDirectory("test-data");
        Path artifacts = Paths.get(testData.toString(), "artifacts").toAbsolutePath();
        Files.createDirectories(artifacts);

        // Add data to artifacts folder
        for (Map.Entry<String, String> artifactFile : ARTIFACT_FILE_MAP.entrySet()) {
            String groupPath = GROUP.replace(DOT, File.separator);
            Path testDir = Paths.get(artifacts.toString(), groupPath, artifactFile.getKey(),
                    VERSION);
            Files.createDirectories(testDir);
            Path testFile = Paths.get(testDir.toString(), artifactFile.getValue());

            if (!Files.exists(testFile)) {
                Files.createFile(testFile);
            }
            String testContent = "Just some data for " + artifactFile.getKey();
            Files.writeString(testFile, testContent);

            Path shaFile = Paths.get(testFile.toString() + DOT + SHA_1);
            if (!Files.exists(shaFile)) {
                Files.createFile(shaFile);
            }

            String sha1 = HashUtil.sha1(testContent);
            Files.writeString(shaFile, sha1);
        }

        return artifacts;
    }

    private ContainerRegistryDetails getContainerRegistryDetails() throws IOException {
        URL urlCatalog = getRegistryURL("_catalog");
        String catalogs = readHTTPData(urlCatalog);

        JsonObject catalogJson = new JsonObject(catalogs);
        JsonArray repositories = catalogJson.getJsonArray("repositories");

        String repo = repositories.getString(0);
        URL urlTags = getRegistryURL(repo + "/tags/list");
        String tagList = readHTTPData(urlTags);
        JsonObject tagListJson = new JsonObject(tagList);

        JsonArray tagsJson = tagListJson.getJsonArray("tags");
        List<String> tags = tagsJson.getList();

        ContainerRegistryDetails containerRegistryDetails = new ContainerRegistryDetails();
        containerRegistryDetails.repoName = repo;
        containerRegistryDetails.tags = tags;

        return containerRegistryDetails;
    }

    private String readHTTPData(URL url) throws IOException {
        StringBuilder result = new StringBuilder();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            for (String line; (line = reader.readLine()) != null;) {
                result.append(line);
            }
        } finally {
            conn.disconnect();
        }
        return result.toString();
    }

    private URL getRegistryURL(String path) throws IOException {
        return new URL("http://" + container.getHost() + ":" + port + "/v2/" + path);
    }

    class ContainerRegistryDetails {
        String repoName;
        String digest;
        List<String> tags;
    }
}

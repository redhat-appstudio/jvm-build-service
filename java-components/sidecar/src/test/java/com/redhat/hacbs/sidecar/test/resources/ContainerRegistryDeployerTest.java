package com.redhat.hacbs.sidecar.test.resources;

import static io.restassured.RestAssured.given;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import io.quarkus.logging.Log;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@QuarkusTest
@QuarkusTestResource(value = ContainerRegistryDeployerTestResource.class, restrictToAnnotatedClass = true)
public class ContainerRegistryDeployerTest {

    @ConfigProperty(name = "registry.host", defaultValue = "quay.io")
    String host;
    @ConfigProperty(name = "registry.port", defaultValue = "443")
    int port;
    @ConfigProperty(name = "registry.owner", defaultValue = "hacbs")
    String owner;
    @ConfigProperty(name = "registry.repository", defaultValue = "artifact-deployments")
    String repository;
    @ConfigProperty(name = "registry.insecure", defaultValue = "false")
    boolean insecure;

    private static final String GROUP = "com.company.foo";
    private static final String VERSION = "3.25.8";
    private static final Map<String, String> ARTIFACT_FILE_MAP = Map.of(
            "foo-bar", "foobar-3.25.8.jar",
            "foo-baz", "foobaz-3.25.8.jar");
    private static final String DOT = ".";
    private static final String SHA_1 = "sha1";

    private static final String EXPECTED_TAG_1 = "ddb962f47b1e1b33a3c59ce0e5a4a403c4e01373eca7fc6e992281f0c6324cc2";
    private static final String EXPECTED_TAG_2 = "f697132e6cc3e55c91e070ac80eabe7ff4a79ac336323fdadfaa191876d2b0d0";

    @Test
    public void testDeployArchive() throws IOException {

        // Here we just make sure we can create images.
        Path createTestTarGz = createTestTarGz();
        Log.infof("Using test tar.gz: " + createTestTarGz);
        try (InputStream inputStream = Files.newInputStream(createTestTarGz)) {

            given().body(inputStream).contentType(ContentType.BINARY)
                    .when().post("/deploy")
                    .then()
                    .statusCode(204);

        }

        // Now we validate that the image and tags exist in the registry
        ContainerRegistryDetails containerRegistryDetails = getContainerRegistryDetails();

        Assert.assertTrue(containerRegistryDetails.repoName.startsWith(owner + "/" + repository));
        Assert.assertTrue(containerRegistryDetails.tags.contains(EXPECTED_TAG_1));
        Assert.assertTrue(containerRegistryDetails.tags.contains(EXPECTED_TAG_2));
    }

    private Path createTestTarGz() throws IOException {
        Path artifacts = Paths.get("target/test-data/artifacts").toAbsolutePath();
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

            String sha1 = sha1(testContent);
            Files.writeString(shaFile, sha1);
        }

        // Now tar.gz the folder
        String tarFileName = "target/" + artifacts.getFileName().toString() + ".tar.gz";

        try (OutputStream fOut = Files.newOutputStream(Paths.get(tarFileName));
                BufferedOutputStream buffOut = new BufferedOutputStream(fOut);
                GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(buffOut);
                TarArchiveOutputStream tOut = new TarArchiveOutputStream(gzOut)) {

            Files.walkFileTree(artifacts, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attributes) throws IOException {

                    if (!attributes.isSymbolicLink()) {
                        Path targetFile = artifacts.relativize(file);

                        TarArchiveEntry tarEntry = new TarArchiveEntry(
                                file.toFile(), targetFile.toString());

                        tOut.putArchiveEntry(tarEntry);
                        Files.copy(file, tOut);
                        tOut.closeArchiveEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

            });

            tOut.finish();
            fOut.flush();
        }
        return Paths.get(tarFileName);
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
        return new URL("http://" + host + ":" + port + "/v2/" + path);
    }

    private String sha1(String value) {
        return sha1(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha1(byte[] value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(value);
            StringBuilder sb = new StringBuilder(40);
            for (int i = 0; i < digest.length; ++i) {
                sb.append(Integer.toHexString((digest[i] & 0xFF) | 0x100).substring(1, 3));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    class ContainerRegistryDetails {
        String repoName;
        List<String> tags;
    }
}

package com.redhat.hacbs.container.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.LogRecord;

import jakarta.inject.Inject;

import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.image.json.OciManifestTemplate;
import com.redhat.hacbs.common.images.ociclient.LocalImage;
import com.redhat.hacbs.common.images.ociclient.OCIRegistryClient;
import com.redhat.hacbs.container.results.ResultsUpdater;
import com.redhat.hacbs.resources.util.HashUtil;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.test.LogCollectingTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = LogCollectingTestResource.class, restrictToAnnotatedClass = true, initArgs = @ResourceArg(name = LogCollectingTestResource.LEVEL, value = "FINE"))
public class MavenDeployTest {
    private static final String GROUP = "com.company.foo";
    private static final String VERSION = "3.25.8";
    public static final String FOO_BAR = "foo-bar";
    public static final String FOO_BAZ = "foo-baz";
    private static final String DOT = ".";
    private static final String SHA_1 = "sha1";
    public static final String COMMIT = "3cf2d99b47f0a05466d1d0a2e09d8740faeda149";
    public static final String REPO = "https://github.com/foo/bar";

    // ArtifactName -> [ collection of Artifacts ]
    private final Map<String, Set<String>> ARTIFACT_FILE_MAP = Map.of(
            FOO_BAR, Set.of("foo-bar-" + VERSION + ".jar", "foo-bar-" + VERSION + "-tests.jar"),
            FOO_BAZ, Set.of("foo-baz-" + VERSION + ".pom"));

    @Inject
    ResultsUpdater resultsUpdater;

    @Inject
    BootstrapMavenContext mvnContext;

    @BeforeEach
    public void clearLogs() {
        LogCollectingTestResource.current().clear();
    }

    @Test
    public void testDeploy() throws IOException {
        Path onDiskRepo = createDeploymentRepo();
        Path source = Files.createTempDirectory("hacbs");
        Path deployment = Files.createTempDirectory("deployment");
        Files.writeString(source.resolve("pom.xml"), "");

        MavenDeployCommand deployCommand = new MavenDeployCommand() {
            @Override
            OCIRegistryClient getRegistryClient() {
                return new OCIRegistryClient("", "", "", Optional.empty(), false) {
                    @Override
                    public Optional<LocalImage> pullImage(String tagOrDigest) {
                        return Optional.of(new LocalImage() {

                            @Override
                            public int getLayerCount() {
                                return 1;
                            }

                            @Override
                            public OciManifestTemplate getManifest() {
                                return null;
                            }

                            @Override
                            public DescriptorDigest getDescriptorDigest() {
                                return null;
                            }

                            @Override
                            public String getDigestHash() {
                                return null;
                            }

                            @Override
                            public void pullLayer(int layer, Path target) throws IOException {
                                FileUtils.copyDirectory(onDiskRepo.toFile(), target.resolve("artifacts").toFile());
                            }

                            @Override
                            public void pullLayer(int layer, Path outputPath, Consumer<Long> blobSizeListener, Consumer<Long> writtenByteCountListener) throws IOException {
                                FileUtils.copyDirectory(onDiskRepo.toFile(), outputPath.resolve("artifacts").toFile());
                            }
                        });

                    }
                };
            }
        };
        deployCommand.mvnCtx = mvnContext;
        deployCommand.mvnPassword = Optional.empty();
        deployCommand.mvnRepo = deployment.toAbsolutePath().toUri().toString();

        deployCommand.run();
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream().anyMatch(r -> LogCollectingTestResource.format(r)
                .contains("Deploying [com.company.foo:foo-baz:pom:3.25.8]")));
        assertTrue(logRecords.stream().anyMatch(r -> LogCollectingTestResource.format(r)
                .contains(
                        "Deploying [com.company.foo:foo-bar:jar:tests:3.25.8, com.company.foo:foo-bar:jar:3.25.8, com.company.foo:foo-bar:pom:3.25.8]")));

        File[] files = Paths.get(deployment.toString(), "com/company/foo/foo-bar/3.25.8").toFile().listFiles();
        assertNotNull(files);
        assertEquals(9, files.length);
    }

    private Path createDeploymentRepo()
            throws IOException {
        Path testData = Files.createTempDirectory("test-data");
        Path artifacts = Paths.get(testData.toString(), "artifacts").toAbsolutePath();
        Files.createDirectories(artifacts);

        // Add data to artifacts folder
        for (Map.Entry<String, Set<String>> artifactFiles : ARTIFACT_FILE_MAP.entrySet()) {
            String groupPath = GROUP.replace(DOT, File.separator);
            Path testDir = Paths.get(artifacts.toString(), groupPath, artifactFiles.getKey(),
                    VERSION);
            Files.createDirectories(testDir);
            for (String value : artifactFiles.getValue()) {
                Path testFile = Paths.get(testDir.toString(), value);
                Files.createFile(testFile);
                String testContent = "Just some data for " + artifactFiles.getKey();
                Files.writeString(testFile, testContent);
                Path shaFile = Paths.get(testFile + DOT + SHA_1);
                Files.createFile(shaFile);
                String sha1 = HashUtil.sha1(Files.readAllBytes(testFile));
                Files.writeString(shaFile, sha1);
            }
            Optional<String> jarFile = artifactFiles.getValue().stream().filter(s -> s.endsWith(VERSION + ".jar")).findAny();
            if (jarFile.isPresent()) {
                Path pomFile = Paths.get(testDir.toString(), jarFile.get().replace(".jar", ".pom"));
                Model model = new Model();
                model.setArtifactId(artifactFiles.getKey());
                model.setGroupId(GROUP);
                model.setVersion(VERSION);
                new MavenXpp3Writer().write(new FileWriter(pomFile.toFile()), model);
            }
        }
        return artifacts;
    }
}

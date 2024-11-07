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
import java.util.logging.LogRecord;

import jakarta.inject.Inject;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    // ArtifactName -> [ collection of Artifacts ]
    private final Map<String, Set<String>> ARTIFACT_FILE_MAP = Map.of(
            FOO_BAR, Set.of("foo-bar-" + VERSION + ".jar", "foo-bar-" + VERSION + "-tests.jar"),
            FOO_BAZ, Set.of("foo-baz-" + VERSION + ".pom"));

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

        DeployCommand deployCommand = new DeployCommand();
        deployCommand.mvnCtx = mvnContext;
        deployCommand.mvnPassword = Optional.empty();
        deployCommand.accessToken = Optional.empty();
        deployCommand.mvnRepo = deployment.toAbsolutePath().toUri().toString();
        deployCommand.artifactDirectory = onDiskRepo.toString();

        deployCommand.run();
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();

        assertTrue(logRecords.stream().anyMatch(r -> LogCollectingTestResource.format(r).contains("no pom file found with files")));
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

        Files.createFile(Paths.get(artifacts.toString(), "test-file.txt"));

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

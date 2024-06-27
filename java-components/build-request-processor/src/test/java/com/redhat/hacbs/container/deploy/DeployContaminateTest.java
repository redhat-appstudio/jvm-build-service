package com.redhat.hacbs.container.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.LogRecord;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.container.results.ResultsUpdater;
import com.redhat.hacbs.resources.util.HashUtil;

import io.quarkus.test.LogCollectingTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = LogCollectingTestResource.class, restrictToAnnotatedClass = true, initArgs = @ResourceArg(name = LogCollectingTestResource.LEVEL, value = "FINE"))
public class DeployContaminateTest {
    private static final String GROUP = "com.company.foo";
    private static final String VERSION = "3.25.8";
    public static final String FOOBAR = "foobar";
    public static final String FOOBAZ = "foobaz";
    private static final String DOT = ".";
    private static final String SHA_1 = "sha1";
    private static final String COMMIT = "3cf2d99b47f0a05466d1d0a2e09d8740faeda149";
    private static final String REPO = "https://github.com/foo/bar";
    private static final Map<String, String> ARTIFACT_FILE_MAP = Map.of(
        FOOBAR, "foobar-" + VERSION + "-tests.jar",
        FOOBAZ, "foobaz-" + VERSION + ".jar");

    @Inject
    ResultsUpdater resultsUpdater;

    @BeforeEach
    public void clearLogs() {
        LogCollectingTestResource.current().clear();
    }

    @Test
    public void testDeployOnlyContaminated() throws IOException, URISyntaxException {
        Path testData = Files.createTempDirectory("test-data");
        Path onDiskRepo = Paths.get(testData.toString(), "artifacts").toAbsolutePath();
        onDiskRepo.toFile().mkdirs();
        Path source = Files.createTempDirectory("hacbs");
        Files.writeString(source.resolve("pom.xml"), "");

        BuildVerifyCommand testDeployment = new BuildVerifyCommand(null, resultsUpdater);
        testDeployment.deploymentPath = onDiskRepo.toAbsolutePath();
        testDeployment.scmUri = REPO;
        testDeployment.commit = COMMIT;
        testDeployment.allowedSources = Set.of("redhat", "rebuilt"); // Default value

        try {
            testDeployment.run();
            fail("No exception thrown");
        } catch (Exception e) {
            List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
            assertTrue(e.getMessage().contains("Deploy failed"));
            assertTrue(logRecords.stream()
                    .anyMatch(r -> LogCollectingTestResource.format(r)
                            .contains("No content to deploy found in deploy directory")));
        }
    }

    @Test
    public void testDeployWithContaminated()
            throws IOException, URISyntaxException {
        Path onDiskRepo = createDeploymentRepo();
        Path source = Files.createTempDirectory("hacbs");
        Files.writeString(source.resolve("pom.xml"), "");

        BuildVerifyCommand testDeployment = new BuildVerifyCommand(null, resultsUpdater);
        testDeployment.deploymentPath = onDiskRepo.toAbsolutePath();
        testDeployment.buildId = "some-id";
        testDeployment.scmUri = REPO;
        testDeployment.commit = COMMIT;
        testDeployment.allowedSources = Set.of("redhat", "rebuilt"); // Default value

        testDeployment.run();
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();

        assertThat(logRecords).map(LogCollectingTestResource::format).contains("foobar-3.25.8-tests.jar was contaminated by org.jboss.metadata:jboss-metadata-common:9.0.0.Final from central", "GAVs to deploy: [com.company.foo:foobaz:3.25.8, com.company.foo:foobar:3.25.8]");
        assertThat(logRecords).extracting("message").doesNotContain("Removing");
        assertThat(logRecords).map(LogCollectingTestResource::format).contains("Extracted classifier 'tests' for artifact 'foobar' and version '3.25.8'");
    }

    @Test
    public void testCodeArtifactRegex() {
        var m = BuildVerifyCommand.CODE_ARTIFACT_PATTERN
                .matcher("https://demo-151537584421.d.codeartifact.us-east-1.amazonaws.com/maven/jbs-demo/");
        Assertions.assertTrue(m.matches());
        Assertions.assertEquals("demo", m.group(1));
        Assertions.assertEquals("jbs-demo", m.group(2));
    }

    private Path createDeploymentRepo()
            throws IOException, URISyntaxException {
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

            if (artifactFile.getKey().contains(FOOBAR)) {
                JarOutputStream jar = new JarOutputStream(new FileOutputStream(testFile.toString()));
                Path iconClass = Paths.get(getClass().getResource("/").toURI())
                        .resolve("../../../cli/src/test/resources/Icon.class").normalize();
                JarEntry entry = new JarEntry(iconClass.getFileName().toString());
                jar.putNextEntry(entry);
                jar.write(Files.readAllBytes(iconClass));
                jar.closeEntry();
                jar.close();
            } else {
                Files.createFile(testFile);
                String testContent = "Just some data for " + artifactFile.getKey();
                Files.writeString(testFile, testContent);
            }

            Path shaFile = Paths.get(testFile + DOT + SHA_1);
            Files.createFile(shaFile);
            String sha1 = HashUtil.sha1(Files.readAllBytes(testFile));
            Files.writeString(shaFile, sha1);
        }

        return artifacts;
    }
}

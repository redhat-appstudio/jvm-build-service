package com.redhat.hacbs.container.analyser.deploy;

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

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

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
    public static final String FOO_BAR = "foo-bar";
    public static final String FOO_BAZ = "foo-baz";
    private static final String DOT = ".";
    private static final String SHA_1 = "sha1";
    public static final String COMMIT = "3cf2d99b47f0a05466d1d0a2e09d8740faeda149";
    public static final String REPO = "https://github.com/foo/bar";
    private Map<String, String> ARTIFACT_FILE_MAP = Map.of(
            FOO_BAR, "foobar-" + VERSION + "-tests.jar",
            FOO_BAZ, "foobaz-" + VERSION + ".jar");

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

        TestDeployment testDeployment = new TestDeployment(null, resultsUpdater);
        testDeployment.deploymentPath = onDiskRepo.toAbsolutePath();
        testDeployment.imageId = "test-image";
        testDeployment.scmUri = REPO;
        testDeployment.commit = COMMIT;
        testDeployment.sourcePath = source.toAbsolutePath();
        testDeployment.allowedSources = Set.of("redhat", "rebuilt"); // Default value

        try {
            testDeployment.run();
            fail("No exception thrown");
        } catch (Exception e) {
            List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
            assertTrue(e.getMessage().contains("deploy failed"));
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

        TestDeployment testDeployment = new TestDeployment(null, resultsUpdater);
        testDeployment.deploymentPath = onDiskRepo.toAbsolutePath();
        testDeployment.imageId = "test-image";
        testDeployment.scmUri = REPO;
        testDeployment.commit = COMMIT;
        testDeployment.sourcePath = source.toAbsolutePath();
        testDeployment.allowedSources = Set.of("redhat", "rebuilt"); // Default value

        testDeployment.run();
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        //        logRecords.forEach(r -> System.out.println("*** " + LogCollectingTestResource.format(r)));
        assertTrue(logRecords.stream()
                .anyMatch(r -> LogCollectingTestResource.format(r).contains(
                        "com/company/foo/foo-bar/3.25.8/foobar-3.25.8-tests.jar was contaminated by org.jboss.metadata:jboss-metadata-common:9.0.0.Final from central")));
        assertTrue(logRecords.stream().noneMatch(r -> r.getMessage().contains("Removing")));
        assertTrue(logRecords.stream().anyMatch(r -> LogCollectingTestResource.format(r)
                .contains("GAVs to deploy: [com.company.foo:foo-bar:3.25.8, com.company.foo:foo-baz:3.25.8")));
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

            if (artifactFile.getKey().contains(FOO_BAR)) {
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

    public static class TestDeployment extends DeployCommand {
        public TestDeployment(BeanManager beanManager, ResultsUpdater resultsUpdater) {
            super(beanManager, resultsUpdater);
        }

        @Override
        protected void doDeployment(Path sourcePath, Path logsPath, Set<String> gavs)
                throws Exception {
            System.out.println("Skipping doDeployment for " + deploymentPath + " from " + sourcePath);
        }
    }
}

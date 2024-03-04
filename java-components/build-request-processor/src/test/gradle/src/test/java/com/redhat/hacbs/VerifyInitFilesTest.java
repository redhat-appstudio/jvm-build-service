package com.redhat.hacbs;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VerifyInitFilesTest {
    @Rule
    public TemporaryFolder testProjectDir = new TemporaryFolder();
    private File buildFile;

    @Before
    public void setup()
            throws IOException {
        buildFile = new File(testProjectDir.newFolder().toString(), "build.gradle");
    }

    @Test
    public void testHelloWorldTask()
            throws IOException, URISyntaxException {
        Path gradleRootDirectory = Paths.get(Objects.requireNonNull(getClass().getClassLoader().getResource(".")).toURI())
                .getParent().getParent().getParent().getParent();
        Files.copy(Paths.get(gradleRootDirectory.toString(), "build.gradle"),
                new File(buildFile.getParentFile(), "build.gradle").toPath());
        List<String> arguments = new ArrayList<>();
        arguments.add("build");

        File[] initScripts = new File(gradleRootDirectory.getParent().getParent().toString(), "main/resources/gradle")
                .listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return name.endsWith(".gradle");
                    }
                });
        if (initScripts == null) {
            throw new RuntimeException("Init script directory not found");
        }
        for (File initScript : initScripts) {
            arguments.add("--init-script");
            arguments.add(initScript.getAbsolutePath());
        }
        Map<String, String> env = Collections.singletonMap("CACHE_URL", "https://repo.maven.apache.org/maven2/");

        for (String version : new String[] { "4.10.3", "5.6.4", "6.1.1", "6.4.1", "6.6.1",
                "6.8.3", "7.0.2", "7.2", "7.4.2", "8.0.2", "8.2.1", "8.4" }) {

            BuildResult result = GradleRunner.create()
                    .withEnvironment(env)
                    .withGradleVersion(version)
                    .withProjectDir(buildFile.getParentFile())
                    .withArguments(arguments)
                    .forwardOutput()
                    .build();

            assertTrue(result.getOutput().contains("Hello world!"));
            assertEquals(SUCCESS, result.task(":build").getOutcome());
        }
    }
}

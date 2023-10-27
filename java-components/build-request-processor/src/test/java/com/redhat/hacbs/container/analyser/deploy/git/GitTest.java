package com.redhat.hacbs.container.analyser.deploy.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.logging.LogRecord;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.TagOpt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.LogCollectingTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = LogCollectingTestResource.class, restrictToAnnotatedClass = true, initArgs = @ResourceArg(name = LogCollectingTestResource.LEVEL, value = "FINE"))
public class GitTest {

    @BeforeEach
    public void clearLogs() {
        LogCollectingTestResource.current().clear();
    }

    @Test
    public void parseScmURI()
            throws URISyntaxException {
        String result = Git.parseScmURI("https://github.com/apache/commons-codec.git");
        assertEquals("apache--commons-codec", result);
        result = Git.parseScmURI("https://gitlab.com/rnc/testRepo");
        assertEquals("rnc--testRepo", result);
        result = Git.parseScmURI("file:///rnc/testRepo");
        assertEquals("rnc--testRepo", result);
    }

    @Test
    public void testPush()
            throws IOException, URISyntaxException, GitAPIException {
        Path initialRepo = Files.createTempDirectory("initial-repo");
        Path testRepo = Files.createTempDirectory("test-repo");
        String testRepoURI = "file://" + testRepo;
        try (var testRepository = org.eclipse.jgit.api.Git.init().setDirectory(testRepo.toFile()).call();
                var initialRepository = org.eclipse.jgit.api.Git.init().setDirectory(initialRepo.toFile()).call()) {
            Path repoRoot = Paths.get(Objects.requireNonNull(getClass().getResource("/")).toURI()).getParent().getParent()
                    .getParent().getParent();
            FileUtils.copyDirectory(new File(repoRoot.toFile(), ".git"), new File(initialRepo.toFile(), ".git"));
            if (initialRepository.tagList().call().stream().noneMatch(r -> r.getName().equals("refs/tags/0.1"))) {
                initialRepository.fetch().setTagOpt(TagOpt.FETCH_TAGS).call();
            }

            Git test = new Git() {
                @Override
                public void create(String name) {
                }

                @Override
                public void add(Path path, String commit) {
                }
            };
            test.pushRepository(initialRepo, testRepoURI, "c396268fb90335bde5c9272b9a194c3d4302bf24");

            List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();

            assertTrue(Files.readString(Paths.get(initialRepo.toString(), ".git/config")).contains(testRepoURI));
            assertTrue(logRecords.stream()
                    .anyMatch(r -> LogCollectingTestResource.format(r)
                            .contains("commit c396268fb90335bde5c9272b9a194c3d4302bf24")));
            assertTrue(logRecords.stream()
                    .anyMatch(
                            r -> LogCollectingTestResource.format(r).matches("Updating current origin of.*to " + testRepoURI)));

            assertTrue(testRepository.tagList().call().stream().anyMatch(r -> r.getName().equals("refs/tags/0.1")));
        }
    }

    @Test
    public void testIdentity() throws IOException {
        new GitHub(null, "cekit", null);
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream().anyMatch(r -> LogCollectingTestResource.format(r).matches("Type ORGANISATION")));
        LogCollectingTestResource.current().clear();
        new GitHub(null, "rnc", null);
        logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream().anyMatch(r -> LogCollectingTestResource.format(r).matches("Type USER")));
    }
}

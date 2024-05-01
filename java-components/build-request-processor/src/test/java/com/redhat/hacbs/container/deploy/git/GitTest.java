package com.redhat.hacbs.container.deploy.git;

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
import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.logging.Log;
import io.quarkus.test.LogCollectingTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = LogCollectingTestResource.class, restrictToAnnotatedClass = true, initArgs = @ResourceArg(name = LogCollectingTestResource.LEVEL, value = "FINE"))
public class GitTest {

    private Git test = new Git() {
        @Override
        public void create(String name) {
        }

        @Override
        public void initialise(String name) {
        }

        @Override
        public GitStatus add(Path path, String commit, String imageId) {
            return null;
        }

        @Override
        public GitStatus add(Path path, String commit, String imageId, boolean untracked) {
            return null;
        }

        @Override
        public String split() {
            return null;
        }

        @Override
        public String getName() {
            return null;
        }
    };

    @BeforeEach
    public void clearLogs() {
        LogCollectingTestResource.current().clear();
    }

    @Test
    public void parseScmURI()
            throws URISyntaxException {

        String result = new GitHub().parseScmURI("https://github.com/apache/commons-codec.git");
        assertEquals("github--apache--commons-codec", result);
        result = new GitHub().parseScmURI("https://github.com/rnc/testRepo");
        assertEquals("github--rnc--testRepo", result);
        result = new GitHub().parseScmURI("file:///rnc/testRepo");
        assertEquals("rnc--testRepo", result);
        result = new GitLab().parseScmURI("https://gitlab.com/rnc/testRepo");
        assertEquals("gitlab-rnc-testRepo", result);
        result = new GitLab().parseScmURI("https://www.gitlab.com/rnc/testRepo");
        assertEquals("gitlab-rnc-testRepo", result);
        result = new GitLab().parseScmURI("https://git.eclipse.org/r/jgit/jgit");
        assertEquals("eclipse-jgit-jgit", result);
    }

    @Test
    public void testInitialise() throws IOException {
        var git = Git.builder(null, "rnc", "", true);
        git.initialise("commons-net");
        git.initialise("https://github.com/rnc/commons-net");
        assertEquals("rnc/commons-net", git.getName());
        git = Git.builder("https://gitlab.com", "rnc", "", true);
        git.initialise("https://gitlab.com/rnc/testRepo");
        assertEquals("rnc/testRepo", git.getName());
    }

    @Test
    public void testPush()
            throws IOException, URISyntaxException, GitAPIException {
        Path initialRepo = Files.createTempDirectory("initial-repo");
        Path testRepo = Files.createTempDirectory("test-repo");
        String testRepoURI = testRepo.toUri().toString();
        String imageID = "75ecd81c7a2b384151c990975eb1dd10";
        try (var testRepository = org.eclipse.jgit.api.Git.init().setDirectory(testRepo.toFile()).call();
                var initialRepository = org.eclipse.jgit.api.Git.init().setDirectory(initialRepo.toFile()).call()) {
            Path repoRoot = Paths.get(Objects.requireNonNull(getClass().getResource("/")).toURI()).getParent().getParent()
                    .getParent().getParent();
            FileUtils.copyDirectory(new File(repoRoot.toFile(), ".git"), new File(initialRepo.toFile(), ".git"));
            FileUtils.copyFile(new File(repoRoot.toFile(), ".gitignore"), new File(initialRepo.toFile(), ".gitignore"));
            Path jbs = Path.of(initialRepo.toString(), ".jbs");
            jbs.toFile().mkdir();
            new File(jbs.toFile(), "Dockerfile").createNewFile();
            new File(jbs.toFile(), "run-build.sh").createNewFile();

            if (initialRepository.tagList().call().stream().noneMatch(r -> r.getName().equals("refs/tags/0.1"))) {
                // Don't have the tag and cannot guarantee a fork will have it so fetch from primary repository.
                String newRemote = RandomStringUtils.randomAlphabetic(8);
                Log.infof("Current repo does not have 0.1 tag; configuring %s to fetch it.", newRemote);
                initialRepository.remoteAdd()
                        .setUri(new URIish("https://github.com/redhat-appstudio/jvm-build-service.git")).setName(newRemote)
                        .call();
                initialRepository.fetch().setRefSpecs("refs/tags/0.1:refs/tags/0.1").setTagOpt(TagOpt.NO_TAGS)
                        .setRemote(newRemote).call();
            }

            Git.GitStatus tagResults = test.pushRepository(
                    initialRepo,
                    testRepoURI,
                    "c396268fb90335bde5c9272b9a194c3d4302bf24",
                    imageID, false);

            List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();

            assertTrue(Files.readString(Paths.get(initialRepo.toString(), ".git/config")).contains(testRepoURI));
            assertTrue(logRecords.stream()
                    .anyMatch(r -> LogCollectingTestResource.format(r)
                            .contains("commit c396268fb90335bde5c9272b9a194c3d4302bf24")));
            assertTrue(logRecords.stream()
                    .anyMatch(
                            r -> LogCollectingTestResource.format(r).matches("Updating current origin of.*to " + testRepoURI)));

            List<Ref> tags = testRepository.tagList().call();
            assertEquals(2, tags.size());
            assertTrue(tags.stream().anyMatch(r -> r.getName().equals("refs/tags/0.1-75ecd81c7a2b384151c990975eb1dd10")));

            var found = tags.stream().filter(t -> Repository.shortenRefName(t.getName()).matches(tagResults.tag)).findFirst();
            assertTrue(found.isPresent());
            assertTrue(tagResults.url.contains(testRepoURI));
            assertTrue(tagResults.sha.matches(testRepository.getRepository()
                    .getRefDatabase()
                    .peel(found.get())
                    .getPeeledObjectId()
                    .getName()));
        }
    }


    @Test
    public void testPush2()
        throws IOException, GitAPIException {
        Path initialRepo = Files.createTempDirectory("initial-repo");
        Path testRepo = Files.createTempDirectory("test-repo");
        String testRepoURI = testRepo.toUri().toString();
        String imageID = "75ecd81c7a2b384151c990975eb1dd10";
        try (var testRepository = org.eclipse.jgit.api.Git.init().setDirectory(testRepo.toFile()).call();
            var initialRepository = org.eclipse.jgit.api.Git.cloneRepository().setURI("https://github.com/rnc/example-maven.git").setDirectory(initialRepo.toFile()).call()) {
            Ref head = initialRepository.getRepository().getRefDatabase().findRef("HEAD");

            // Simulate modified file by preprocessor.
            Path pom = Paths.get(initialRepo.toString(), "pom.xml");
            Files.writeString(pom, Files.readString(pom).replace("<javaVersion>11</javaVersion>", "<javaVersion>17</javaVersion>"));

            // Simulate JBS adding files
            Path jbs = Path.of(initialRepo.toString(), ".jbs");
            jbs.toFile().mkdir();
            new File(jbs.toFile(), "Dockerfile").createNewFile();
            new File(jbs.toFile(), "run-build.sh").createNewFile();

            Git.GitStatus tagResults = test.pushRepository(
                initialRepo,
                testRepoURI,
                head.getObjectId().getName(),
                imageID, true);

            List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();

            assertTrue(Files.readString(Paths.get(initialRepo.toString(), ".git/config")).contains(testRepoURI));
            assertTrue(logRecords.stream()
                .anyMatch(r -> LogCollectingTestResource.format(r)
                    .contains("commit 5b05d61932ec510edf6737f55843a73ec5fdec82")));
            assertTrue(logRecords.stream()
                .anyMatch(
                    r -> LogCollectingTestResource.format(r).matches("Updating current origin of.*to " + testRepoURI)));

            List<Ref> tags = testRepository.tagList().call();
            assertEquals(2, tags.size());
            assertTrue(tags.stream().anyMatch(r -> r.getName().equals("refs/tags/0.6.8.Final-75ecd81c7a2b384151c990975eb1dd10")));

            var found = tags.stream().filter(t -> Repository.shortenRefName(t.getName()).matches(tagResults.tag)).findFirst();
            assertTrue(found.isPresent());
            assertTrue(tagResults.url.contains(testRepoURI));
            assertTrue(tagResults.sha.matches(testRepository.getRepository()
                .getRefDatabase()
                .peel(found.get())
                .getPeeledObjectId()
                .getName()));
        }
    }

    @Test
    public void testPush3()
        throws IOException, GitAPIException {
        Path initialRepo = Files.createTempDirectory("initial-repo");
        Path testRepo = Files.createTempDirectory("test-repo");
        String testRepoURI = testRepo.toUri().toString();
        String imageID = "75ecd81c7a2b384151c990975eb1dd10";
        try (var testRepository = org.eclipse.jgit.api.Git.init().setDirectory(testRepo.toFile()).call();
            var ignored = org.eclipse.jgit.api.Git.cloneRepository().setURI("https://github.com/rnc/commons-net.git").setDirectory(initialRepo.toFile()).call() ) {

            Git.GitStatus tagResults = test.pushRepository(
                initialRepo,
                testRepoURI,
                "e0aa3a2aace28061bb4ee7dcdd87e5960d173037",
                imageID,
                false);

            List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();

            assertTrue(Files.readString(Paths.get(initialRepo.toString(), ".git/config")).contains(testRepoURI));
            assertTrue(logRecords.stream()
                .anyMatch(r -> LogCollectingTestResource.format(r)
                    .contains("commit e0aa3a2aace28061bb4ee7dcdd87e5960d173037")));
            assertTrue(logRecords.stream()
                .anyMatch(
                    r -> LogCollectingTestResource.format(r).matches("Updating current origin of.*to " + testRepoURI)));

            List<Ref> tags = testRepository.tagList().call();
            assertEquals(2, tags.size());
            assertTrue(tags.stream().anyMatch(r -> r.getName().equals("refs/tags/e0aa3a2aace28061bb4ee7dcdd87e5960d173037-75ecd81c7a2b384151c990975eb1dd10")));

            var found = tags.stream().filter(t -> Repository.shortenRefName(t.getName()).matches(tagResults.tag)).findFirst();
            assertTrue(found.isPresent());
            assertTrue(tagResults.url.contains(testRepoURI));
            assertTrue(tagResults.sha.matches(testRepository.getRepository()
                .getRefDatabase()
                .peel(found.get())
                .getPeeledObjectId()
                .getName()));
        }
    }


    @Test
    public void testIdentity() throws IOException {
        new GitHub(null, "cekit", null, true);
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream().anyMatch(r -> LogCollectingTestResource.format(r).matches("Type ORGANISATION")));
        LogCollectingTestResource.current().clear();
        new GitHub(null, "rnc", null, true);
        logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream().anyMatch(r -> LogCollectingTestResource.format(r).matches("Type USER")));
    }
}

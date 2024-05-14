package com.redhat.hacbs.common.tools.repo;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

/**
 * Utility class that can create a pull request against the build recipe repository
 */
public class RepositoryChange {

    private static final String DEFAULT_DATA_REPO = "redhat-appstudio/jvm-build-data";

    public static String getContent(String filePath) {
        try {
            var gh = GitHub.connect();
            var me = gh.getMyself().getLogin();
            GHRepository mainRepo = gh.getRepository(getJBSDataRepository());
            System.out.println("Using GitHub user " + me + " with repository " + mainRepo.getFullName());
            try {
                try (InputStream in = mainRepo.getFileContent(filePath).read()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (GHFileNotFoundException e) {
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String createPullRequest(String branchName, String commitMessage, String filePath, byte[] fileContent) {
        try {
            var gh = GitHub.connect();
            var me = gh.getMyself().getLogin();
            var repo = getJBSDataRepository();
            GHRepository mainRepo = gh.getRepository(repo);
            GHRepository myfork = mainRepo;

            if (DEFAULT_DATA_REPO.equals(repo)) {
                var forks = mainRepo.listForks();
                myfork = null;
                for (GHRepository i : forks.toList()) {
                    if (i.getOwnerName().equals(me)) {
                        myfork = i;
                        break;
                    }
                }
                if (myfork == null) {
                    throw new RuntimeException("Could not find fork of the redhat-appstudio/jvm-build-data repo owned by" + me
                            + ", please fork the repo");
                }
            }

            var ref = myfork.createRef("refs/heads/" + branchName, mainRepo.getBranch("main").getSHA1());

            System.out.println(
                    "Using GitHub user " + me + " with repository " + myfork.getFullName() + " and branch reference " + ref);

            String sha = null;
            try {
                var existing = mainRepo.getFileContent(filePath, "main");
                sha = existing.getSha();
            } catch (GHFileNotFoundException e) {

            }
            System.out.println("Creating commit using sha " + sha + " and message " + commitMessage);

            var commit = myfork.createContent().branch(branchName).path(filePath).content(fileContent)
                    .message(commitMessage)
                    .sha(sha)
                    .commit();
            return mainRepo.createPullRequest(commitMessage, me + ":" + branchName, "main", commitMessage, true).getHtmlUrl()
                    .toExternalForm();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String getJBSDataRepository() {
        var env = System.getenv("JBS_RECIPE_DATABASE");
        if (isEmpty(env)) {
            return "redhat-appstudio/jvm-build-data";
        } else {
            return URI.create(env).getPath().substring(1);
        }
    }
}

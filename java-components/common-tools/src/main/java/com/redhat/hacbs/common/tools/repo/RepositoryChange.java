package com.redhat.hacbs.common.tools.repo;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

/**
 * Utility class that can create a pull request against the build recipe repository
 */
public class RepositoryChange {

    public static String getContent(String filePath) {
        try {
            var gh = GitHub.connect();
            var me = gh.getMyself().getLogin();
            System.out.println("GitHub User: " + me);
            GHRepository mainRepo = gh.getRepository("redhat-appstudio/jvm-build-data");
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
        File homeDir = new File(System.getProperty("user.home"));
        File propertyFile = new File(homeDir, ".github");
        if (!propertyFile.exists()) {
            throw new RuntimeException(
                    "You must create a .github file as specified at https://github-api.kohsuke.org/ to be able to modify the build recipes.");
        }
        //TODO: should not be hard coded
        try {
            var gh = GitHub.connect();
            var me = gh.getMyself().getLogin();
            System.out.println("GitHub User: " + me);
            GHRepository mainRepo = gh.getRepository("redhat-appstudio/jvm-build-data");
            var forks = mainRepo.listForks();
            GHRepository myfork = null;
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
            String sha = null;
            var ref = myfork.createRef("refs/heads/" + branchName, myfork.getBranch("main").getSHA1());
            try {
                var existing = myfork.getFileContent(filePath, ref.getRef());
                sha = existing.getSha();
            } catch (GHFileNotFoundException e) {
                return null;
            }
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

}

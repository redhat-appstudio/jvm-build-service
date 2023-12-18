package com.redhat.hacbs.common.tools.repo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;
import com.redhat.hacbs.recipies.util.FileUtil;

import io.quarkus.logging.Log;

/**
 * Utility class that can create a pull request against the build recipe repository
 */
public class RepositoryChange {

    public static String createPullRequest(String branchName, String commitMessage, PullRequestCreator creator) {
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
            Path checkoutPath = Files.createTempDirectory("open-pr");
            try (Git checkout = Git.cloneRepository().setDirectory(checkoutPath.toFile())
                    .setURI(myfork.getHttpTransportUrl()).call()) {
                System.out.println("Checked out " + myfork.getHttpTransportUrl());
                checkout.remoteAdd().setName("upstream").setUri(new URIish(mainRepo.getHttpTransportUrl())).call();
                checkout.fetch().setRemote("upstream").call();
                checkout.checkout().setName("upstream/main").call();
                checkout.checkout().setCreateBranch(true).setName(branchName)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM).setForceRefUpdate(true).call();
                RecipeLayoutManager recipeLayoutManager = new RecipeLayoutManager(checkoutPath);
                RecipeGroupManager groupManager = new RecipeGroupManager(List.of(recipeLayoutManager));
                creator.makeModifications(checkoutPath, groupManager, recipeLayoutManager);
                //commit the changes
                checkout.add().addFilepattern("scm-info").addFilepattern("build-info").call();
                checkout.commit().setMessage(commitMessage)
                        .setAll(true)
                        .call();

                //push the changes to our fork
                checkout.push().setForce(true).setCredentialsProvider(new GithubCredentials(me)).setRemote("origin").call();

                String head = me + ":" + branchName;
                System.out.println("head:" + head);
                var pr = mainRepo.createPullRequest(commitMessage, head, "main", "");
                String prUrl = pr.getIssueUrl().toExternalForm().replace("https://api.github.com/repos", "https://github.com");
                Log.infof("Created Pull Request: " + prUrl);
                return prUrl;
            } finally {
                FileUtil.deleteRecursive(checkoutPath);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}

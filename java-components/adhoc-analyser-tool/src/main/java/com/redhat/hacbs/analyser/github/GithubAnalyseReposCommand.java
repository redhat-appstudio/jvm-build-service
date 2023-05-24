package com.redhat.hacbs.analyser.github;

import java.io.FileNotFoundException;
import java.util.ArrayList;

import jakarta.inject.Inject;

import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import com.redhat.hacbs.analyser.config.RepoConfig;
import com.redhat.hacbs.analyser.data.scm.ScmManager;

import picocli.CommandLine;

@CommandLine.Command(name = "analyse", description = "Pulls information from guthub")
public class GithubAnalyseReposCommand implements Runnable {

    @Inject
    RepoConfig config;

    @Override
    public void run() {
        try (ScmManager manager = ScmManager.create(config.path())) {
            GitHub gitHub = GitHub.connect();
            for (var repo : new ArrayList<>(manager.getAll())) {
                try {
                    if (!repo.getUri().startsWith("https://github.com/")) {
                        continue;
                    }
                    System.out.println(repo.getUri());
                    String ghRepoString = repo.getUri().replace("https://github.com/", "").replace(".git", "");
                    GHRepository gh = gitHub.getRepository(ghRepoString);
                    if (gh.isArchived()) {
                        repo.setDeprecated(true);
                    }
                    if (gh.isFork()) {
                        repo.setDisabled(true);
                        repo.setFailedReason("Is a fork");
                    } else {
                        try {
                            GHOrganization org = gitHub.getOrganization(ghRepoString.split("/")[0]);
                        } catch (FileNotFoundException e) {
                            repo.setDisabled(true);
                            repo.setFailedReason("Is a personal repo");
                        }
                    }
                } catch (FileNotFoundException e) {
                    repo.setDisabled(true);
                    repo.setFailedReason("Did not exist");
                }
                manager.writeData();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

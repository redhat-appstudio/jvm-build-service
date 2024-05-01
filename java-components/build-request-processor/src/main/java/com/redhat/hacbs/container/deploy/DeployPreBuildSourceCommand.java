package com.redhat.hacbs.container.deploy;

import static org.apache.commons.lang3.ObjectUtils.isNotEmpty;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.container.deploy.git.Git;
import com.redhat.hacbs.container.results.ResultsUpdater;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "deploy-pre-build-source")
public class DeployPreBuildSourceCommand implements Runnable {

    final ResultsUpdater resultsUpdater;

    @CommandLine.Option(names = "--task-run-name")
    String taskRun;

    @CommandLine.Option(required = true, names = "--source-path")
    Path sourcePath;

    @CommandLine.Option(required = true, names = "--scm-uri")
    String scmUri;

    @CommandLine.Option(required = true, names = "--scm-commit")
    String commit;

    @CommandLine.Option(names = "--image-id")
    String imageId;

    @ConfigProperty(name = "git.deploy.token")
    Optional<String> gitToken;

    // If endpoint is null then default GitHub API endpoint is used. Otherwise:
    // for GitHub, endpoint like https://api.github.com
    // for GitLib, endpoint like https://gitlab.com
    @CommandLine.Option(names = "--git-url")
    String gitURL;

    @CommandLine.Option(names = "--git-identity")
    String gitIdentity;

    @CommandLine.Option(names = "--git-disable-ssl-verification")
    boolean gitDisableSSLVerification;

    @CommandLine.Option(names = "--git-reuse-repository")
    boolean reuseRepository;

    public DeployPreBuildSourceCommand(ResultsUpdater resultsUpdater) {
        this.resultsUpdater = resultsUpdater;
    }

    public void run() {
        try {
            Git.GitStatus archivedSourceTags = new Git.GitStatus();

            // Save the source first regardless of deployment checks
            if (isNotEmpty(gitIdentity) && gitToken.isPresent()) {
                var git = Git.builder(gitURL, gitIdentity, gitToken.get(), gitDisableSSLVerification);
                if (reuseRepository) {
                    git.initialise(scmUri);
                } else {
                    git.create(scmUri);
                }
                Log.infof("Pushing changes back to URL %s", git.getName());
                archivedSourceTags = git.add(sourcePath, commit, imageId, true);
            }
            if (taskRun != null) {
                String serialisedGitArchive = ResultsUpdater.MAPPER.writeValueAsString(archivedSourceTags);
                Log.infof("Updating results %s for Konflux generation with gitArchiveTags %s",
                    taskRun, serialisedGitArchive);
                resultsUpdater.updateResults(taskRun, Map.of(
                    "GIT_ARCHIVE", serialisedGitArchive));
            }
        } catch (Exception e) {
            Log.error("Konflux generation failed", e);
            throw new RuntimeException(e);
        }
    }
}

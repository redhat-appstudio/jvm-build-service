package com.redhat.hacbs.container.analyser.deploy.git;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import io.quarkus.logging.Log;

public abstract class Git {

    protected CredentialsProvider credentialsProvider;

    public abstract void create(String name)
            throws IOException, URISyntaxException;

    public abstract void add(Path path, String commit)
            throws IOException;

    protected void pushRepository(Path path, String httpTransportUrl, String commit) {
        try (var jGit = org.eclipse.jgit.api.Git.init().setDirectory(path.toFile()).call()) {
            // TODO: Discuss tag naming - should a rebuild produce the same results ? In which case why use a different tag?
            //   As JBS might have changed the code? We could append the image-id i.e.
            //   apache/commons-net.git@rel/commons-net-3.9.0    DependencyBuildStateComplete   75ecd81c7a2b384151c990975eb1dd10
            //   Tag would be
            //      rel/commons-net-3.9.0-75ecd81c7a2b384151c990975eb1dd10
            var tagName = jGit.describe().setTags(true).setTarget(commit).call();
            var jRepo = jGit.getRepository();

            StoredConfig jConfig = jRepo.getConfig();
            Log.infof("Updating current origin of %s to %s", jConfig.getString("remote", "origin", "url"),
                    httpTransportUrl);
            jConfig.setString("remote", "origin", "url", httpTransportUrl);
            jConfig.save();
            Log.infof("Pushing to %s with content from %s (branch %s, commit %s, tag %s)", httpTransportUrl, path,
                    jRepo.getBranch(), commit, tagName);

            // TODO: Discuss use of annotated tag?
            Ref tagRef = jGit.tag().setAnnotated(true).setName(tagName).setForceUpdate(true).call();
            // Push the default branch else GitHub doesn't show the code.
            Iterable<PushResult> results = jGit.push().setForce(true).setRemote("origin").add(jRepo.getBranch()).add(tagRef)
                    .setCredentialsProvider(credentialsProvider).call();

            for (PushResult result : results) {
                result.getRemoteUpdates().forEach(r -> {
                    if (!r.getStatus().equals(RemoteRefUpdate.Status.OK)) {
                        Log.errorf("Push failure " + r);
                        throw new RuntimeException("Failed to push updates due to " + r.getMessage());
                    }
                });
                Log.debugf("Pushed " + result.getMessages() + " " + result.getURI() + " updates: "
                        + result.getRemoteUpdates());
            }
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *
     * @param endpoint URL of the GitHub or GitLab instance.
     * @param identity Might be user or organisation name.
     * @param token Authorisation token.
     * @return Valid Git instance
     * @throws IOException if an error occurs
     */
    public static Git builder(String endpoint, String identity, String token)
            throws IOException {
        // TODO: To be discussed - this could be a bit presumptuous to assume
        //    an on-premise installation will always contain some determinable
        //    information. Alternative would be forcing the user to configure
        //    endpoint, token, AND type [gitlab|github]
        if (endpoint != null && endpoint.contains("gitlab")) {
            return new GitLab(endpoint, identity, token);
        } else {
            return new GitHub(endpoint, identity, token);
        }
    }

    /**
     * Parse an scm URI to split into [user/org] and [repo] which will be concatenated
     * together for the new repository creation.
     *
     * @param scmUri a URL to be parsed
     * @return a reformatted name to use as the new repository name.
     * @throws URISyntaxException if an error occurs.
     */
    protected static String parseScmURI(String scmUri)
            throws URISyntaxException {
        String path = new URI(scmUri).getPath().substring(1);
        String group = path.substring(0, path.lastIndexOf("/"));
        String name = (path.endsWith(".git") ? path.substring(0, path.length() - 4) : path).substring(group.length() + 1);
        return group + "--" + name;
    }
}

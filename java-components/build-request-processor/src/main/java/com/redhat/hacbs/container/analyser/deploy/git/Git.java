package com.redhat.hacbs.container.analyser.deploy.git;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import io.quarkus.logging.Log;

public abstract class Git {

    protected CredentialsProvider credentialsProvider;

    protected boolean disableSSLVerification;

    public abstract void create(String name)
            throws IOException, URISyntaxException;

    public abstract GitStatus add(Path path, String commit, String imageId)
            throws IOException;

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
        return builder(endpoint, identity, token, true);
    }

    /**
     *
     * @param endpoint URL of the GitHub or GitLab instance.
     * @param identity Might be user or organisation name.
     * @param token Authorisation token.
     * @param disableSSLVerification Whether to enable SSLVerification (Default: true).
     * @return Valid Git instance
     * @throws IOException if an error occurs
     */
    public static Git builder(String endpoint, String identity, String token, boolean disableSSLVerification)
            throws IOException {
        // TODO: This could be a bit presumptuous to assume
        //    an on-premise installation will always contain some determinable
        //    information. Alternative would be forcing the user to configure
        //    endpoint, token, AND type [gitlab|github]
        if (endpoint != null && endpoint.contains("gitlab")) {
            return new GitLab(endpoint, identity, token, disableSSLVerification);
        } else {
            return new GitHub(endpoint, identity, token, disableSSLVerification);
        }
    }

    protected GitStatus pushRepository(Path path, String httpTransportUrl, String commit, String imageId) {
        try (var jGit = org.eclipse.jgit.api.Git.init().setDirectory(path.toFile()).call()) {
            // Find the tag name associated with the commit. Then append the unique imageId. This is from the Go code
            // and is a hash of abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path
            // e.g.
            //   apache/commons-net.git@rel/commons-net-3.9.0    DependencyBuildStateComplete   75ecd81c7a2b384151c990975eb1dd10
            // Tag would be
            //   rel/commons-net-3.9.0-75ecd81c7a2b384151c990975eb1dd10
            var tagName = jGit.describe().setTags(true).setTarget(commit).call();
            var jRepo = jGit.getRepository();

            StoredConfig jConfig = jRepo.getConfig();
            Log.infof("Updating current origin of %s to %s", jConfig.getString("remote", "origin", "url"),
                    httpTransportUrl);
            jConfig.setString("remote", "origin", "url", httpTransportUrl);
            if (disableSSLVerification) {
                jConfig.setBoolean("http", null, "sslVerify", false);
            }
            jConfig.save();
            Log.infof("Pushing to %s with content from %s (branch %s, commit %s, tag %s)", httpTransportUrl, path,
                    jRepo.getBranch(), commit, tagName);

            Ref tagRefStable = jGit.tag().setAnnotated(true).setName(tagName + "-" + imageId).setForceUpdate(true).call();
            Ref tagRefUnique = jGit.tag().setAnnotated(true).setName(tagName + "-" + UUID.randomUUID()).setForceUpdate(true)
                    .call();

            Iterable<PushResult> results = jGit.push().setForce(true).setRemote("origin")
                    .add(jRepo.getBranch()) // Push the default branch else GitHub doesn't show the code.
                    .add(tagRefStable)
                    .add(tagRefUnique)
                    .setCredentialsProvider(credentialsProvider).call();

            for (PushResult result : results) {
                result.getRemoteUpdates().forEach(r -> {
                    if (!r.getStatus().equals(RemoteRefUpdate.Status.OK)
                            && !r.getStatus().equals(RemoteRefUpdate.Status.UP_TO_DATE)) {
                        Log.errorf("Push failure " + r);
                        throw new RuntimeException("Failed to push updates due to " + r.getMessage());
                    }
                });
                Log.debugf("Pushed " + result.getMessages() + " " + result.getURI() + " updates: "
                        + result.getRemoteUpdates());
            }

            return new GitStatus(httpTransportUrl, Repository.shortenRefName(tagRefUnique.getName()),
                    jRepo.getRefDatabase().peel(tagRefUnique).getPeeledObjectId().getName());
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Parse an SCM URI to split into [user/org] and [repo] which will be concatenated
     * together for the new repository creation.
     *
     * @param scmUri a URL to be parsed
     * @return a reformatted name to use as the new repository name.
     * @throws URISyntaxException if an error occurs.
     */
    protected String parseScmURI(String scmUri)
            throws URISyntaxException {
        String path = new URI(scmUri).getPath().substring(1);
        String group = path.substring(0, path.lastIndexOf("/"));
        String name = (path.endsWith(".git") ? path.substring(0, path.length() - 4) : path).substring(group.length() + 1);
        return group + groupSplit() + name;
    }

    abstract String groupSplit();

    public static class GitStatus {
        public String url;
        public String tag;
        public String sha;

        public GitStatus() {
        }

        public GitStatus(String url, String tag, String sha) {
            this.url = url;
            this.tag = tag;
            this.sha = sha;
        }

        @Override
        public String toString() {
            return "GitStatus{url='" + url + "', tag='" + tag + "', sha='" + sha + "'}";
        }
    }
}

package com.redhat.hacbs.container.deploy.git;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import io.quarkus.logging.Log;

public abstract class Git {

    protected CredentialsProvider credentialsProvider;

    protected boolean disableSSLVerification;

    protected boolean newGitRepository = false;

    /**
     * Creates a Git repository using the supplied scm name
     * @param name a SCM URI.
     * @throws IOException if an error occurs.
     * @throws URISyntaxException if an error occurs.
     */
    public abstract void create(String name)
            throws IOException, URISyntaxException;

    /**
     * Initialises an existing Git repository using the supplied scm name
     * @param name a SCM URI.
     * @throws IOException if an error occurs.
     */
    public abstract void initialise(String name)
        throws IOException;

    /**
     * Using the repository at path, push all files with the associated commit.
     * @param path the path to the repository.
     * @param commit the commit (tag or hash) to push.
     * @param imageId the string to use for tag uniqueness.
     * @return a {@link GitStatus} object describing the result.
     * @throws IOException if an error occurs.
     */
    public abstract GitStatus add(Path path, String commit, String imageId)
            throws IOException;

    /**
     * Using the repository at path, push all files with the associated commit.
     * @param path the path to the repository.
     * @param commit the commit (tag or hash) to push.
     * @param imageId the string to use for tag uniqueness.
     * @param untracked whether to create a new commit containing any untracked/modified files.
     * @param workflow whether to remove any preexisting workflow files.
     * @return a {@link GitStatus} object describing the result.
     * @throws IOException if an error occurs.
     */
    public abstract GitStatus add(Path path, String commit, String imageId, boolean untracked, boolean workflow)
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

    protected GitStatus pushRepository(Path path, String httpTransportUrl, String commit, String imageId, boolean untracked,
        boolean workflow) {
        try (var jGit = org.eclipse.jgit.api.Git.init().setDirectory(path.toFile()).call()) {
            // Find the tag name associated with the commit. Then append the unique imageId. This is from the Go code
            // and is a hash of abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path
            // e.g.
            //   apache/commons-net.git@rel/commons-net-3.9.0    DependencyBuildStateComplete   75ecd81c7a2b384151c990975eb1dd10
            // Tag would be
            //   rel/commons-net-3.9.0-75ecd81c7a2b384151c990975eb1dd10
            // While we could use jGit describe as there may be more than one tag associated with 'commit' we will parse the
            // ref database (using code from tagList command) instead.
            //var tagNameFromDescribe = jGit.describe().setTags(true).setTarget(commit).call();
            var objectId = ObjectId.fromString(commit);
            var jRepo = jGit.getRepository();
            if (new File(jGit.getRepository().getDirectory(), "shallow").exists()) {
                Log.warnf("Git repository is shallow repository - converting.");
                jGit.fetch().setUnshallow(true).call();
            }
            @SuppressWarnings("deprecation")
            var tagName = jRepo.getRefDatabase().getRefsByPrefix(Constants.R_TAGS).stream().
                filter(ref -> objectId.equals(jRepo.peel(ref).getPeeledObjectId()) || objectId.equals(ref.getObjectId())).
                map(r -> r.getName().replace("refs/tags/", "")).
                // Exclude our own tag format of tag-<uuid|commit> if we've previously run and fallen back to branch
                // commit for the tag name.
                filter(t -> !t.startsWith(commit)).
                findFirst().orElse(null);

            if (tagName == null) {
                // No tag found - might be using a branch; default to commit.
                tagName = commit;
            }
            var branchName = tagName + "-jbs-branch";
            var createBranch = jGit.branchList().call().stream().map(Ref::getName).noneMatch(("refs/heads/" + branchName)::equals);
            Log.infof("Will create branch %s for tag %s and branch name %s", createBranch, tagName, branchName);
            var ref = jGit.checkout().setStartPoint(tagName).setName(branchName).setCreateBranch(createBranch).call();
            StoredConfig jConfig = jRepo.getConfig();
            Log.infof("Updating current origin of %s to %s", jConfig.getString("remote", "origin", "url"),
                    httpTransportUrl);
            jConfig.setString("remote", "origin", "url", httpTransportUrl);
            if (disableSSLVerification) {
                jConfig.setBoolean("http", null, "sslVerify", false);
            }
            jConfig.save();

            Log.infof("Pushing to %s with content from %s (commit %s, tag %s, ref %s)", httpTransportUrl, path, commit, tagName, ref);

            if (workflow) {
                jGit.rm().addFilepattern(getWorkflowPath()).call();
            }
            if (untracked) {
                // setRenormalize configures line endings. JGit defaults to true while git defaults to false. Keep it as per CLI.
                jGit.add().setRenormalize(false).addFilepattern(".").call();
            }
            if (untracked || workflow) {
                RevCommit revCommit = jGit.commit().setNoVerify(true).setAuthor("JBS", "").setMessage("JBS modifications for workflows and pre-build changes.").call();
                List<DiffEntry> diffs = jGit.diff()
                    .setOldTree(prepareTreeParser(jRepo, commit))
                    .setNewTree(prepareTreeParser(jRepo, revCommit.getName()))
                    .call();
                Log.infof("Committed JBS changes to %s", diffs.stream().map(this::formatDiffEntry).sorted().collect(Collectors.toList()));
            }

            Ref tagRefStable = null;
            if (!tagName.endsWith(imageId)) {
                // Avoid repeatedly concatenating the imageId to an existing tag.
                tagRefStable = jGit.tag().setAnnotated(true).setName(tagName + "-" + imageId).setForceUpdate(true).call();
            }
            Ref tagRefUnique = jGit.tag().setAnnotated(true).setName(tagName + "-" + UUID.randomUUID()).setForceUpdate(true)
                    .call();

            PushCommand pushCommand = jGit.push().setForce(true).setRemote("origin")
                    .add(tagRefUnique)
                    .setCredentialsProvider(credentialsProvider);
            if (tagRefStable != null) {
                pushCommand.add(tagRefStable);
            }
            // If it is a new repository we should push
            // the default branch else it doesn't show the code
            if (newGitRepository) {
                pushCommand.add(jRepo.getBranch());
            }

            Iterable<PushResult> results = pushCommand.call();

            for (PushResult result : results) {
                result.getRemoteUpdates().forEach(r -> {
                    if (!r.getStatus().equals(RemoteRefUpdate.Status.OK)
                            && !r.getStatus().equals(RemoteRefUpdate.Status.UP_TO_DATE)) {
                        Log.errorf("Push failure:\n" + result.getMessages());
                        throw new RuntimeException("Failed to push updates due to " + r);
                    }
                });
                Log.infof("Pushed " + result.getURI() + " updates: "
                        + result.getRemoteUpdates());
            }

            return new GitStatus(httpTransportUrl, Repository.shortenRefName(tagRefUnique.getName()),
                    jRepo.getRefDatabase().peel(tagRefUnique).getPeeledObjectId().getName());
        } catch (GitAPIException | IOException e) {
            Log.error("Caught JGit problems processing the push", e);
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
        URI uri = new URI(scmUri);
        String host = uri.getHost();
        if (host != null) {
            host = host.substring(0, host.lastIndexOf("."));
            int subdomain = host.indexOf(".");
            if (subdomain != -1) {
                host = host.substring(subdomain + 1);
            }
        }
        String path = uri.getPath().substring(1);
        String group = path.substring(0, path.lastIndexOf("/"));
        int nonGroupPathIndex = group.indexOf("/");
        String name = (path.endsWith(".git") ? path.substring(0, path.length() - 4) : path).substring(group.length() + 1);
        if (nonGroupPathIndex != -1) {
            group = group.substring(nonGroupPathIndex + 1);
        }
        return (host == null ? "" : host + split()) + group + split() + name;
    }

    protected String processRepoName(String name) {
        var index = name.lastIndexOf('/');
        var scmRepo = name.substring(index == -1 ? 0 : index + 1);
        index = scmRepo.lastIndexOf(".git");
        return scmRepo.substring(0, index == -1 ? scmRepo.length() : index);
    }

    abstract String split();

    public abstract String getName();

    public abstract String getWorkflowPath();


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


    private String formatDiffEntry(DiffEntry diffEntry) {
        return switch (diffEntry.getChangeType()) {
            case ADD -> diffEntry.getNewPath();
            case COPY, RENAME -> diffEntry.getOldPath() + "->" + diffEntry.getNewPath();
            case DELETE, MODIFY -> diffEntry.getOldPath();
        };
    }

    // https://github.com/centic9/jgit-cookbook/blob/master/src/main/java/org/dstadler/jgit/porcelain/DiffFilesInCommit.java
    private static AbstractTreeIterator prepareTreeParser(Repository repository, String objectId) throws IOException {
        // from the commit we can build the tree which allows us to construct the TreeParser
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(repository.resolve(objectId));
            RevTree tree = walk.parseTree(commit.getTree().getId());
            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }
            walk.dispose();
            return treeParser;
        }
    }
}

package com.redhat.hacbs.container.deploy.git;

import static org.apache.commons.lang3.ObjectUtils.isNotEmpty;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHubBuilder;

import io.quarkus.logging.Log;

public class GitHub extends Git {
    enum Type {
        USER,
        ORGANISATION
    }

    // GITHUB_URL in GitHubClient is package private.
    private static final String GITHUB_URL = "https://api.github.com";

    private final org.kohsuke.github.GitHub github;

    private final String owner;

    private Type type;

    private GHRepository repository;

    public GitHub(String endpoint, String identity, String token, boolean ssl)
            throws IOException {
        if (isNotEmpty(token)) {
            github = new GitHubBuilder().withEndpoint(endpoint == null ? GITHUB_URL : endpoint)
                    .withOAuthToken(token)
                    .build();
        } else {
            github = new GitHubBuilder().withEndpoint(endpoint == null ? GITHUB_URL : endpoint)
                    .build();
        }
        owner = identity;
        credentialsProvider = new UsernamePasswordCredentialsProvider(token, "");
        disableSSLVerification = ssl;

        switch (github.getUser(identity).getType()) {
            case "User" -> type = Type.USER;
            case "Organization" -> type = Type.ORGANISATION;
        }
        Log.infof("Type %s", type);
    }

    GitHub() {
        owner = null;
        github = null;
    }


    @Override
    public void initialise(String name) throws IOException {
        var scmRepo = processRepoName(name);
        if (type == Type.USER) {
            repository = github.getUser(owner).getRepository(scmRepo);
        } else {
            repository = github.getOrganization(owner).getRepository(scmRepo);
        }
        if (repository == null) {
            throw new IOException("Unable to find the repository " + scmRepo);
        }
    }



    @Override
    public void create(String scmUri)
            throws IOException, URISyntaxException {
        String name = parseScmURI(scmUri);
        if (type == Type.USER) {
            repository = github.getUser(owner).getRepository(name);
            if (repository == null) {
                Log.infof("Creating repository with name %s", name);
                repository = github.createRepository(name)
                    .wiki(false)
                    .defaultBranch("main")
                    .projects(false)
                    .private_(false).create();
                newGitRepository = true;
            } else {
                Log.warnf("Repository %s already exists", name);
            }
        } else {
            repository = github.getOrganization(owner).getRepository(name);
            if (repository == null) {
                Log.infof("Creating repository with name %s", name);
                repository = github.getOrganization(owner).createRepository(name)
                    .wiki(false)
                    .defaultBranch("main")
                    .projects(false)
                    .private_(false).create();
                newGitRepository = true;
            } else {
                Log.warnf("Repository %s already exists", name);
            }
        }
    }

    @Override
    public GitStatus add(Path path, String commit, String imageId) {
        if (repository == null) {
            throw new RuntimeException("Call create first");
        }
        return pushRepository(path, repository.getHttpTransportUrl(), commit, imageId, false);
    }

    @Override
    public GitStatus add(Path path, String commit, String imageId, boolean untracked) {
        if (repository == null) {
            throw new RuntimeException("Call create first");
        }
        return pushRepository(path, repository.getHttpTransportUrl(), commit, imageId, untracked);
    }

    @Override
    String split() {
        return "--";
    }

    @Override
    public String getName() {
        return repository.getFullName();
    }
}

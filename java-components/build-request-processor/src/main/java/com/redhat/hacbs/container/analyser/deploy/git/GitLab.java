package com.redhat.hacbs.container.analyser.deploy.git;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Project;
import org.gitlab4j.api.models.ProjectFilter;
import org.gitlab4j.api.models.Visibility;

import io.quarkus.logging.Log;

public class GitLab extends Git {
    private final GitLabApi gitLabApi;

    private final String owner;

    private Project project;

    public GitLab(String endpoint, String identity, String token, boolean ssl) {
        gitLabApi = new GitLabApi(endpoint, token);
        owner = identity;
        credentialsProvider = new UsernamePasswordCredentialsProvider("", token);
        disableSSLVerification = ssl;

        if (disableSSLVerification) {
            gitLabApi.setIgnoreCertificateErrors(true);
        }
    }

    GitLab() {
        owner = null;
        gitLabApi = null;
    }

    @Override
    public void create(String scmUri)
            throws URISyntaxException {
        String name = parseScmURI(scmUri);
        try {
            project = gitLabApi.getProjectApi().getUserProjectsStream(owner, new ProjectFilter().withSearch(name))
                    .filter(p -> p.getName().equals(name))
                    .findFirst().orElse(null);
            if (project != null) {
                Log.warnf("Repository %s already exists", name);
            } else {
                // Can't set public visibility after creation for some reason with this API.
                Log.infof("Creating repository with name %s", name);
                project = gitLabApi.getProjectApi().createProject(name,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        Visibility.PUBLIC,
                        null,
                        null);
            }
        } catch (GitLabApiException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public GitStatus add(Path path, String commit, String imageId)
            throws IOException {
        if (project == null) {
            throw new RuntimeException("Call create first");
        }
        return pushRepository(path, project.getHttpUrlToRepo(), commit, imageId);
    }

    @Override
    String split() {
        return "-";
    }
}

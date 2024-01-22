package com.redhat.hacbs.container.analyser.deploy.mavenrepository;

import com.amazonaws.services.codeartifact.AWSCodeArtifact;

public record CodeArtifactRepository(AWSCodeArtifact client, String domain, String repository) {
}

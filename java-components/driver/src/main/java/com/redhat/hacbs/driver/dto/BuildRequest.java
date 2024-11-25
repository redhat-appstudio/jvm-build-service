package com.redhat.hacbs.driver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.jackson.Jacksonized;

@RequiredArgsConstructor
@Data
@Jacksonized
@Builder(builderClassName = "Builder")
@JsonIgnoreProperties(ignoreUnknown = true)
public class BuildRequest {

    private final String recipeImage;
    private final String buildTool;
    private final String buildToolVersion;
    private final String javaVersion;

    // TODO: Is this related to the name of the project (i.e. name returned from /v2/projects/{id}) or the build-config
    private final String projectName;
    private final String scmUrl;
    // TODO: Do we need both?
    private final String scmRevision;
    // private final String scmTag;

    private final String buildScript;

    // TODO: Are we currently using this? How does PNC pass this?
    // private final String workingDirectory;

    private final String repositoryDependencyUrl;

    private final String repositoryDeployUrl;

    private final String repositoryBuildContentId;

    private final String namespace;

    // TODO: NYI
    // private final String podMemoryOverride;
}

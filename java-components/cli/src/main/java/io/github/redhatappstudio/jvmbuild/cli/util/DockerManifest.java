package io.github.redhatappstudio.jvmbuild.cli.util;

import java.util.ArrayList;

import javax.annotation.Generated;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Generated("jsonschema2pojo")
public class DockerManifest {

    @JsonProperty("Config")
    public String config;
    @JsonProperty("RepoTags")
    public ArrayList<String> repoTags;
    @JsonProperty("Layers")
    public ArrayList<String> layers;

}

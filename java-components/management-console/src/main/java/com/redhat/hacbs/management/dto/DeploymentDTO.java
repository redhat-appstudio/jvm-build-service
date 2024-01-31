package com.redhat.hacbs.management.dto;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

public class DeploymentDTO {
    @Schema(required = true)
    public String namespace;
    @Schema(required = true)
    public String name;
    @Schema(required = true)
    public boolean analysisComplete;
    @Schema(required = true)
    public List<ImageDTO> images = new ArrayList<>();

}

package com.redhat.hacbs.resources.model.v1alpha1;

public class ModelConstants {
    public static final String GROUP = "jvmbuildservice.io";
    public static final String VERSION = "v1alpha1";
    public static final String CLEAR_CACHE = GROUP + "/clear-cache";
    public static final String LAST_CLEAR_CACHE = GROUP + "/last-clear-cache";

    public static final String JBS_CONFIG_NAME = "jvm-build-config";

    public static final String REBUILD = GROUP + "/rebuild";

    public static final String DEPENDENCY_CREATED = GROUP + "/dependency-created";

    public static final String ARTIFACT_BUILD_COMPLETE = "ArtifactBuildComplete";
    public static final String ARTIFACT_BUILD_NEW = "ArtifactBuildNew";
    public static final String ARTIFACT_BUILD_DISCOVERING = "ArtifactBuildDiscovering";
    public static final String ARTIFACT_BUILD_MISSING = "ArtifactBuildMissing";
    public static final String ARTIFACT_BUILD_BUILDING = "ArtifactBuildBuilding";
    public static final String ARTIFACT_BUILD_FAILED = "ArtifactBuildFailed";

    public static final String DEPENDENCY_BUILD_COMPLETE = "DependencyBuildStateComplete";
    public static final String DEPENDENCY_BUILD_BUILDING = "DependencyBuildStateBuilding";
    public static final String DEPENDENCY_BUILD_FAILED = "DependencyBuildStateFailed";
    public static final String DEPENDENCY_BUILD_CONTAMINATED = "DependencyBuildStateContaminated";
    public static final String DEPENDENCY_BUILD_DEPLOYING = "DependencyBuildStateDeploying";

    public static final String REUSE_SCM = GROUP + "/deploy-source-reuse-scm";

}

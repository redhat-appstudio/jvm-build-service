package com.redhat.hacbs.gradle;

import org.gradle.api.Project;
import org.gradle.tooling.provider.model.ToolingModelBuilder;

/**
 * Custom model builder.
 */
public class BuildInformationBuilder implements ToolingModelBuilder {
    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals(BuildInformation.class.getName());
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        GradleBuildInformation buildInformation = new GradleBuildInformation(project);
        DefaultBuildInformation model = new DefaultBuildInformation();
        model.setJavaVersion(buildInformation.getJavaVersion());
        model.setPlugins(buildInformation.getPlugins());
        return model;
    }
}

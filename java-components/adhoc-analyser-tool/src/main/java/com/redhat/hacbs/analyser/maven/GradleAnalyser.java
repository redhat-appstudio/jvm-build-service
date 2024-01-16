package com.redhat.hacbs.analyser.maven;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.gradle.GradleBuild;
import org.gradle.tooling.model.gradle.ProjectPublications;

import com.redhat.hacbs.recipes.GAV;

public class GradleAnalyser {

    public static MavenProject doProjectDiscovery(Path projectRoot) {
        Map<GAV, MavenModule> modules = new HashMap<>();

        try (ProjectConnection connection = GradleConnector.newConnector()
                .forProjectDirectory(projectRoot.toFile())
                .connect()) {
            for (var project : connection.getModel(GradleBuild.class).getProjects().getAll()) {
                try (ProjectConnection c2 = GradleConnector.newConnector()
                        .forProjectDirectory(project.getProjectDirectory())
                        .connect()) {
                    for (var i : c2.getModel(ProjectPublications.class).getPublications().getAll()) {
                        GAV key = new GAV(i.getId().getGroup(), i.getId().getName(), i.getId().getVersion());
                        modules.put(key, new MavenModule(key, null, project.getProjectDirectory().toPath()));
                    }
                }
            }
        }

        return new MavenProject(modules);
    }
}

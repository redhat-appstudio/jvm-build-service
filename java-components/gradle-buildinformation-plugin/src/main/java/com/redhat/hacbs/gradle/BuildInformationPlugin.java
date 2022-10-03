package com.redhat.hacbs.gradle;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

import javax.inject.Inject;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.build.BuildEnvironment;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

/**
 * Gradle build information plugin.
 */
public class BuildInformationPlugin implements Plugin<Project> {
    private static final String INIT_SCRIPT = "import com.redhat.hacbs.gradle.BuildInformationPlugin\n" +
            "\n" +
            "initscript {\n" +
            "    dependencies {\n" +
            "        classpath files(\"@PLUGIN_JAR@\")\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "allprojects {\n" +
            "    apply plugin: BuildInformationPlugin\n" +
            "}\n";

    private final ToolingModelBuilderRegistry registry;

    /**
     *
     * Constructs a new Gradle build information plugin with the given registry.
     *
     * @param registry the registry
     */
    @Inject
    public BuildInformationPlugin(ToolingModelBuilderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void apply(Project project) {
        if (project == project.getRootProject()) {
            registry.register(new BuildInformationBuilder());
            project.getTasks().register("buildinformation",
                    task -> task
                            .doLast(s -> {
                                GradleBuildInformation buildInformation = new GradleBuildInformation(project);
                                project.getLogger().lifecycle("javaVersion={}", buildInformation.getJavaVersion());
                                project.getLogger().lifecycle("plugins={}", buildInformation.getPlugins());
                            }));
        }
    }

    private static Path createGradleInit() throws IOException, URISyntaxException {
        Path init = Files.createTempFile("init-", ".gradle");
        CodeSource codeSource = BuildInformation.class.getProtectionDomain().getCodeSource();
        Path pluginJar = Paths.get(codeSource.getLocation().toURI());
        String initScript = INIT_SCRIPT.replace("@PLUGIN_JAR@", pluginJar.toAbsolutePath().toString());
        Files.write(init, initScript.getBytes(StandardCharsets.UTF_8));
        return init;
    }

    /**
     * Gets the build information for the given project directory.
     *
     * @param projectDirectory the project directory
     * @return the build information
     */
    public static GradleBuildInformation getBuildInformation(Path projectDirectory) {
        GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(projectDirectory.toFile())
                .useBuildDistribution();

        try (ProjectConnection connection = connector.connect()) {
            BuildEnvironment environment = connection.model(BuildEnvironment.class).get();
            ModelBuilder<BuildInformation> modelBuilder = connection.model(BuildInformation.class);

            try {
                modelBuilder.withArguments("--init-script", createGradleInit().toString());
                BuildInformation buildInformation = modelBuilder.get();
                GradleBuildInformation gradleBuildInformation = new GradleBuildInformation();
                gradleBuildInformation.setJavaVersion(buildInformation.getJavaVersion());
                gradleBuildInformation.setPlugins(buildInformation.getPlugins());
                gradleBuildInformation.setGradleVersion(environment.getGradle().getGradleVersion());
                return gradleBuildInformation;
            } catch (IOException | URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
    }
}

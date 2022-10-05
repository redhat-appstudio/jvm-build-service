package com.redhat.hacbs.gradle;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.COLLECTION;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for the {@code com.redhat.hacbs.buildinformation} plugin.
 */
class BuildInformationPluginTest {
    private static final int JAVA_VERSION = 4;

    private static final String SCRIPT = "plugins {\n" +
            "    `java`\n" +
            "}\n" +
            "\n" +
            "java {\n" +
            "    sourceCompatibility = JavaVersion.VERSION_1_" + JAVA_VERSION + "\n" +
            "    targetCompatibility = JavaVersion.VERSION_1_" + (JAVA_VERSION + 1) + "\n" +
            "}\n";

    private static final String GRADLE_VERSION = "7.4";

    private static final String WRAPPER = "distributionBase=GRADLE_USER_HOME\n" +
            "distributionPath=wrapper/dists\n" +
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + GRADLE_VERSION + "-bin.zip\n" +
            "zipStoreBase=GRADLE_USER_HOME\n" +
            "zipStorePath=wrapper/dists\n";

    private static final String[] PLUGINS = {
            "com.redhat.hacbs.gradle.BuildInformationPlugin",
            "org.gradle.api.plugins.HelpTasksPlugin",
            "org.gradle.buildinit.plugins.BuildInitPlugin",
            "org.gradle.buildinit.plugins.WrapperPlugin",
            "org.gradle.language.base.plugins.LifecycleBasePlugin",
            "org.gradle.api.plugins.BasePlugin",
            "org.gradle.api.plugins.JvmEcosystemPlugin",
            "org.gradle.api.plugins.ReportingBasePlugin",
            "org.gradle.api.plugins.JavaBasePlugin$Inject",
            "org.gradle.testing.base.plugins.TestSuiteBasePlugin",
            "org.gradle.api.plugins.JvmTestSuitePlugin",
            "org.gradle.api.plugins.JavaPlugin",
            "org.gradle.kotlin.dsl.provider.plugins.KotlinScriptRootPlugin",
            "org.gradle.kotlin.dsl.provider.plugins.KotlinScriptBasePlugin" };

    @Test
    void testBuildInformationTask(@TempDir Path basedir) throws IOException {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("com.redhat.hacbs.buildinformation");
        assertThat(project.getTasks().findByName("buildinformation")).isNotNull();
        Path buildGradle = basedir.resolve("build.gradle.kts");
        Files.write(buildGradle, SCRIPT.getBytes(StandardCharsets.UTF_8));
        Path gradleWrapperProperties = basedir.resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(gradleWrapperProperties.getParent());
        Files.write(gradleWrapperProperties, WRAPPER.getBytes(StandardCharsets.UTF_8));
        GradleBuildInformation buildInformation = BuildInformationPlugin.getBuildInformation(basedir);
        assertThat(buildInformation.getGradleVersion()).isEqualTo(GRADLE_VERSION);
        assertThat(buildInformation.getJavaVersion()).isEqualTo(JAVA_VERSION + 1);
        assertThat(buildInformation.getPlugins()).asInstanceOf(COLLECTION).containsExactlyInAnyOrder(PLUGINS);
    }
}

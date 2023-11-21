package com.redhat.hacbs.container.analyser.build;

import static com.redhat.hacbs.container.analyser.build.BuildInfo.GRADLE;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.JDK;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.MAVEN;
import static com.redhat.hacbs.container.analyser.build.InvocationBuilder.findClosestVersions;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.recipies.build.BuildRecipeInfo;
import com.redhat.hacbs.recipies.tools.BuildToolInfo;

public class InvocationBuilderTestCase {

    public static CacheBuildInfoLocator buildInfoLocator = new CacheBuildInfoLocator() {
        @Override
        public BuildRecipeInfo resolveBuildInfo(String scmUrl, String version) {
            return null;
        }

        @Override
        public List<String> findRepositories(Set<String> repositories) {
            return null;
        }

        @Override
        public List<BuildToolInfo> lookupBuildToolInfo(String name) {
            return List.of();
        }

        @Override
        public List<String> lookupPluginInfo(String name) {
            return switch (name) {
                case MAVEN -> List.of("org.glassfish.copyright:glassfish-copyright-maven-plugin",
                        "org.sonatype.plugins:nexus-staging-maven-plugin",
                        "com.mycila:license-maven-plugin",
                        "org.codehaus.mojo:findbugs-maven-plugin", // older version of this will break the build on our version of maven
                        "de.jjohannes:gradle-module-metadata-maven-plugin");
                case GRADLE -> List.of("kotlin.gradle.targets.js", "org.jetbrains.dokka");
                default -> List.of();
            };
        }
    };

    @Test
    public void testClosestVersionMatch() {
        Assertions.assertEquals(Set.of("1.1"), findClosestVersions(List.of("1.0", "1.1", "1.3"), "1.1"));
        Assertions.assertEquals(Set.of("1.1.2"), findClosestVersions(List.of("1.0.1", "1.1.2", "1.3.3"), "1.1"));
        Assertions.assertEquals(Set.of("1.0.1", "1.1.2", "1.3.3"),
                findClosestVersions(List.of("1.0.1", "1.1.2", "1.3.3"), "1"));
        Assertions.assertEquals(Set.of("3.1"), findClosestVersions(List.of("3.1", "4.2"), "1.1"));
        Assertions.assertEquals(Set.of("4.2"), findClosestVersions(List.of("3.1", "4.2"), "6.1"));
        Assertions.assertEquals(Set.of("3.1", "5.2"), findClosestVersions(List.of("3.1", "5.2", "6.7"), "4.1"));

    }

    @Test
    public void testInvocationMapping() {
        InvocationBuilder builder = newBuilder();
        builder.addToolInvocation(MAVEN, List.of("install"));
        var result = builder.build(buildInfoLocator);
        Assertions.assertEquals(3, result.invocations.size());
        Assertions.assertTrue(
                result.invocations
                        .contains(new Invocation(List.of("install"), Map.of(MAVEN, "3.8.0", JDK, "11"), MAVEN,
                                buildInfoLocator.lookupPluginInfo(MAVEN))));

        builder = newBuilder();
        builder.addToolInvocation(MAVEN, List.of("install"));
        builder.addToolInvocation(GRADLE, List.of("build"));
        builder.minJavaVersion(new JavaVersion("11"));
        builder.maxJavaVersion(new JavaVersion("11"));
        result = builder.build(buildInfoLocator);
        Assertions.assertEquals(4, result.invocations.size());
        Assertions.assertTrue(result.invocations
                .contains(
                        new Invocation(List.of("install"), Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "11"), MAVEN,
                                buildInfoLocator.lookupPluginInfo(MAVEN))));
        Assertions.assertTrue(result.invocations
                .contains(new Invocation(List.of("build"), Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "11"),
                        GRADLE, buildInfoLocator.lookupPluginInfo(GRADLE))));

        builder = newBuilder();
        builder.addToolInvocation(MAVEN, List.of("mvn", "install"));
        builder.addToolInvocation(GRADLE, List.of("gradle", "build"));
        builder.minJavaVersion(new JavaVersion("11"));
        builder.maxJavaVersion(new JavaVersion("11"));
        builder.discoveredToolVersion(GRADLE, "5.2");
        result = builder.build(buildInfoLocator);
        Assertions.assertEquals(2, result.invocations.size());
        Assertions.assertTrue(result.invocations
                .contains(
                        new Invocation(List.of("mvn", "install"), Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "11"), MAVEN,
                                buildInfoLocator.lookupPluginInfo(MAVEN))));
        Assertions.assertTrue(result.invocations
                .contains(new Invocation(List.of("gradle", "build"), Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "11"),
                        GRADLE, buildInfoLocator.lookupPluginInfo(GRADLE))));

    }

    private InvocationBuilder newBuilder() {
        return new InvocationBuilder(null, Map.of(
                MAVEN, List.of("3.8.0"),
                GRADLE, List.of("5.4", "6.1"),
                BuildInfo.JDK, List.of("8", "11", "17")), "1");
    }
}

package com.redhat.hacbs.container.analyser.build;

import static com.redhat.hacbs.container.analyser.build.BuildInfo.GRADLE;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.JDK;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.MAVEN;
import static com.redhat.hacbs.container.analyser.build.InvocationBuilder.findClosestVersions;
import static com.redhat.hacbs.container.analyser.build.JavaVersion.JAVA_11;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.recipes.tools.BuildToolInfo;

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
            if (name.equals("jdk")) {
                return List.of(
                        new BuildToolInfo().setReleaseDate("2018-09-25").setVersion("11").setMaxJdkVersion("11")
                                .setMinJdkVersion("11"),
                        new BuildToolInfo().setReleaseDate("2014-03-18").setVersion("8").setMaxJdkVersion("8")
                                .setMinJdkVersion("8"),
                        new BuildToolInfo().setReleaseDate("2011-07-28").setVersion("7").setMaxJdkVersion("7")
                                .setMinJdkVersion("7"));
            } else if (name.equals("gradle")) {
                return List.of(
                        new BuildToolInfo().setReleaseDate("2019-04-26").setVersion("5.0").setMaxJdkVersion("12")
                                .setMinJdkVersion("8"),
                        new BuildToolInfo().setReleaseDate("2019-08-26").setVersion("5.4").setMaxJdkVersion("12")
                                .setMinJdkVersion("8"),
                        new BuildToolInfo().setReleaseDate("2019-09-26").setVersion("6.0").setMaxJdkVersion("13")
                                .setMinJdkVersion("8"),
                        new BuildToolInfo().setReleaseDate("2020-01-15").setVersion("6.1").setMaxJdkVersion("13")
                                .setMinJdkVersion("8"));
            }
            return List.of();
        }

        @Override
        public List<String> lookupDisabledPlugins(String tool) {
            return switch (tool) {
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
        builder.setCommitTime(System.currentTimeMillis());
        builder.addToolInvocation(MAVEN, List.of("install"));
        var result = builder.build(buildInfoLocator);
        Assertions.assertEquals(8, result.invocations.size());
        Assertions.assertTrue(
                result.invocations
                        .contains(
                                new Invocation(List.of("install", "org.apache.maven.plugins:maven-deploy-plugin:3.1.1:deploy"),
                                        Map.of(MAVEN, "3.8.0", JDK, "11"), MAVEN,
                                        buildInfoLocator.lookupDisabledPlugins(MAVEN))));

        builder = newBuilder();
        builder.setCommitTime(System.currentTimeMillis());
        builder.addToolInvocation(MAVEN, List.of("install"));
        builder.addToolInvocation(GRADLE, List.of("build"));
        builder.minJavaVersion(JAVA_11);
        builder.maxJavaVersion(JAVA_11);
        result = builder.build(buildInfoLocator);
        Assertions.assertEquals(8, result.invocations.size());
        Assertions.assertTrue(result.invocations
                .contains(
                        new Invocation(List.of("install", "org.apache.maven.plugins:maven-deploy-plugin:3.1.1:deploy"),
                                Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "11"), MAVEN,
                                buildInfoLocator.lookupDisabledPlugins(MAVEN))));
        Assertions.assertTrue(result.invocations
                .contains(new Invocation(List.of("build"), Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "11"),
                        GRADLE, buildInfoLocator.lookupDisabledPlugins(GRADLE))));

        builder = newBuilder();
        builder.addToolInvocation(MAVEN, List.of("mvn", "install"));
        builder.addToolInvocation(GRADLE, List.of("gradle", "build"));
        builder.minJavaVersion(JAVA_11);
        builder.maxJavaVersion(JAVA_11);
        builder.discoveredToolVersion(GRADLE, "5.2");
        result = builder.build(buildInfoLocator);
        Assertions.assertEquals(4, result.invocations.size());
        Assertions.assertTrue(result.invocations
                .contains(
                        new Invocation(List.of("mvn", "install", "org.apache.maven.plugins:maven-deploy-plugin:3.1.1:deploy"),
                                Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "11"), MAVEN,
                                buildInfoLocator.lookupDisabledPlugins(MAVEN))));
        Assertions.assertTrue(result.invocations
                .contains(new Invocation(List.of("gradle", "build"), Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "11"),
                        GRADLE, buildInfoLocator.lookupDisabledPlugins(GRADLE))));

    }

    @Test
    public void testInvocationMappingDateFiltering() throws ParseException {
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
        InvocationBuilder builder = newBuilder();
        builder.setCommitTime(df.parse("2013-01-01").getTime());
        builder.addToolInvocation(MAVEN, List.of("install"));
        var result = builder.build(buildInfoLocator);

        Assertions.assertEquals(6, result.invocations.size());
        Assertions.assertTrue(
                result.invocations
                        .contains(
                                new Invocation(List.of("install", "org.apache.maven.plugins:maven-deploy-plugin:3.1.1:deploy"),
                                        Map.of(MAVEN, "3.8.0", JDK, "8"), MAVEN,
                                        buildInfoLocator.lookupDisabledPlugins(MAVEN))));

        builder = newBuilder();
        builder.setCommitTime(df.parse("2019-05-06").getTime());
        builder.addToolInvocation(GRADLE, List.of("build"));
        result = builder.build(buildInfoLocator);
        Assertions.assertEquals(2, result.invocations.size());
        Assertions.assertTrue(
                result.invocations
                        .contains(new Invocation(List.of("build"), Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "8"), GRADLE,
                                buildInfoLocator.lookupDisabledPlugins(GRADLE))));
        Assertions.assertTrue(
                result.invocations
                        .contains(new Invocation(List.of("build"), Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "11"), GRADLE,
                                buildInfoLocator.lookupDisabledPlugins(GRADLE))));

        builder = newBuilder();
        builder.setCommitTime(df.parse("2010-05-06").getTime());
        builder.addToolInvocation(GRADLE, List.of("build"));
        result = builder.build(buildInfoLocator);
        Assertions.assertEquals(0, result.invocations.size());

        builder = newBuilder();
        builder.setCommitTime(df.parse("2019-10-06").getTime());
        builder.addToolInvocation(GRADLE, List.of("build"));
        result = builder.build(buildInfoLocator);
        Assertions.assertEquals(4, result.invocations.size());
        Assertions.assertTrue(
                result.invocations
                        .contains(new Invocation(List.of("build"), Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "8"), GRADLE,
                                buildInfoLocator.lookupDisabledPlugins(GRADLE))));
        Assertions.assertTrue(
                result.invocations
                        .contains(new Invocation(List.of("build"), Map.of(MAVEN, "3.8.0", GRADLE, "5.4", JDK, "11"), GRADLE,
                                buildInfoLocator.lookupDisabledPlugins(GRADLE))));
        Assertions.assertTrue(
                result.invocations
                        .contains(new Invocation(List.of("build"), Map.of(MAVEN, "3.8.0", GRADLE, "6.1", JDK, "8"), GRADLE,
                                buildInfoLocator.lookupDisabledPlugins(GRADLE))));
        Assertions.assertTrue(
                result.invocations
                        .contains(new Invocation(List.of("build"), Map.of(MAVEN, "3.8.0", GRADLE, "6.1", JDK, "11"), GRADLE,
                                buildInfoLocator.lookupDisabledPlugins(GRADLE))));
    }

    @Test
    public void testInvocationOrderMaven() {
        InvocationBuilder builder = newBuilder();
        builder.setCommitTime(System.currentTimeMillis());
        builder.addToolInvocation(MAVEN, List.of("install"));
        var result = builder.build(buildInfoLocator);
        Assertions.assertEquals(8, result.invocations.size());
        Assertions.assertEquals(result.invocations.get(0).getToolVersion().get(JDK), "7");
        Assertions.assertEquals(result.invocations.get(0).getToolVersion().get(MAVEN), "3.9.5");
        Assertions.assertEquals(result.invocations.get(1).getToolVersion().get(MAVEN), "3.9.5");
        Assertions.assertEquals(result.invocations.get(2).getToolVersion().get(MAVEN), "3.9.5");
        Assertions.assertEquals(result.invocations.get(4).getToolVersion().get(JDK), "7");
        Assertions.assertEquals(result.invocations.get(4).getToolVersion().get(MAVEN), "3.8.0");
        Assertions.assertEquals(result.invocations.get(5).getToolVersion().get(MAVEN), "3.8.0");
    }

    @Test
    public void testInvocationOrderGradle() {
        InvocationBuilder builder = newBuilder();
        builder.setCommitTime(System.currentTimeMillis());
        builder.addToolInvocation(GRADLE, List.of("build"));
        var result = builder.build(buildInfoLocator);
        Assertions.assertEquals(4, result.invocations.size());
        Assertions.assertEquals(result.invocations.get(0).getToolVersion().get(JDK), "8");
        Assertions.assertEquals(result.invocations.get(1).getToolVersion().get(JDK), "11");
        Assertions.assertEquals(result.invocations.get(2).getToolVersion().get(JDK), "8");
        Assertions.assertEquals(result.invocations.get(0).getToolVersion().get(GRADLE), "6.1");
        Assertions.assertEquals(result.invocations.get(1).getToolVersion().get(GRADLE), "6.1");
        Assertions.assertEquals(result.invocations.get(2).getToolVersion().get(GRADLE), "5.4");
        Assertions.assertEquals(result.invocations.get(3).getToolVersion().get(GRADLE), "5.4");
    }

    private InvocationBuilder newBuilder() {
        return new InvocationBuilder(null, Map.of(
                MAVEN, List.of("3.8.0", "3.9.5"),
                GRADLE, List.of("5.4", "6.1"),
                BuildInfo.JDK, List.of("7", "8", "11", "17")), "1");
    }
}

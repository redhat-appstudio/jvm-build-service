package com.redhat.hacbs.container.analyser.build;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.recipes.tools.BuildToolInfo;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.test.LogCollectingTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = LogCollectingTestResource.class, restrictToAnnotatedClass = true, initArgs = @ResourceArg(name = LogCollectingTestResource.LEVEL, value = "FINE"))
class LookupBuildInfoCommandTest {

    @BeforeEach
    public void clearLogs() {
        LogCollectingTestResource.current().clear();
    }

    private final String toolVersions = "maven:3.8.8;3.9.5,gradle:8.4;8.3;8.0.2;7.4.2;6.9.2,sbt:1.8.0,ant:1.10.13;1.9.16,jdk:8;11;17;7";

    CacheBuildInfoLocator cacheBuildInfoLocator = new CacheBuildInfoLocator() {
        @Override
        public BuildRecipeInfo resolveBuildInfo(String scmUrl, String version) {
            return null;
        }

        @Override
        public List<String> findRepositories(Set<String> repositories) {
            return List.copyOf(repositories);
        }

        @Override
        public List<BuildToolInfo> lookupBuildToolInfo(String name) {
            return List.of();
        }

        @Override
        public List<String> lookupDisabledPlugins(String tool) {
            return List.of();
        }
    };

    @Test
    public void testBuildAnalysis()
            throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.mavenContext = new BootstrapMavenContext();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        lookupBuildInfoCommand.commit = "7ab205a40853486c1d978e6a7555808b9435407d";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://github.com/codehaus/jaxen.git", new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        assertEquals("jaxen", info.getContextPath());
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream()
                .anyMatch(r -> LogCollectingTestResource.format(r)
                        .contains("Unable to locate a build script within")));
        assertEquals("8", info.invocations.get(0).getToolVersion().get("jdk"));
    }

    @Test
    public void testBuildAnalysisWithContext()
            throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.mavenContext = new BootstrapMavenContext();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        lookupBuildInfoCommand.context = "jaxen";
        lookupBuildInfoCommand.commit = "7ab205a40853486c1d978e6a7555808b9435407d";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://github.com/codehaus/jaxen.git", new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertNull(info.getContextPath());
        assertTrue(logRecords.stream()
                .noneMatch(r -> LogCollectingTestResource.format(r)
                        .contains("Unable to locate a build script within")));
    }

    @Test
    public void testBuildAnalysisFails()
            throws BootstrapMavenException {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.mavenContext = new BootstrapMavenContext();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        lookupBuildInfoCommand.commit = "0750e49665855b4b85487aa3780bf0daf09e8e4c";

        assertThrows(RuntimeException.class,
                () -> lookupBuildInfoCommand.doBuildAnalysis("https://github.com/jakartaee/jaf-api.git", new BuildRecipeInfo(),
                        cacheBuildInfoLocator));
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertEquals(2, logRecords.stream()
                .filter(r -> LogCollectingTestResource.format(r)
                        .contains("Found Maven pom file at"))
                .count());

    }

    @Test
    public void testModelResolver1()
            throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.mavenContext = new BootstrapMavenContext();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        lookupBuildInfoCommand.commit = "ea39ccf03d38b65df7aa5153a1bbddc3197b1597";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://github.com/wso2/balana", new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        assertTrue(info.repositories.contains("https://maven.wso2.org/nexus/content/groups/wso2-public/"));
    }

    @Test
    public void testModelResolver2()
            throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();

        lookupBuildInfoCommand.mavenContext = new BootstrapMavenContext();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        lookupBuildInfoCommand.commit = "02d3e524110982ec2b420ff8f9126707d483374f";
        var info = lookupBuildInfoCommand.doBuildAnalysis(
                "https://github.com/jtablesaw/tablesaw",
                new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        assertTrue(info.repositories.contains("https://jitpack.io"));
    }

    @Test
    public void testModelResolver3()
            throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();

        lookupBuildInfoCommand.mavenContext = new BootstrapMavenContext();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        lookupBuildInfoCommand.commit = "7da937230df898d8d1c05522cdfa5e3931921f8f";
        var info = lookupBuildInfoCommand.doBuildAnalysis(
                "https://github.com/jvm-build-service-test-data/maven-symlink-in-repo",
                new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        assertTrue(info.repositories.contains("https://repo.maven.apache.org/maven2"));
    }

    @Test
    public void testModelResolver4()
            throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();

        lookupBuildInfoCommand.mavenContext = new BootstrapMavenContext();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        lookupBuildInfoCommand.commit = "a586e706aea82dc80fb05bdf59f2a25150ee1801";
        var info = lookupBuildInfoCommand.doBuildAnalysis(
                "https://github.com/javaee/jsonp",
                new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        assertTrue(info.repositories.contains("https://maven.java.net/content/repositories/snapshots"));
    }

    @Test
    public void testBuildAnalysisGradleRelease()
            throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        lookupBuildInfoCommand.commit = "1f6020a3f17d9d88dfd54a31370e91e3361c216b";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://gitlab.ow2.org/asm/asm.git", new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        System.err.println(info.invocations);
        assertTrue(info.invocations.get(0).getCommands().contains("-Prelease"));
    }
}

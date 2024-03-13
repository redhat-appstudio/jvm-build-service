package com.redhat.hacbs.container.analyser.build;

import static com.redhat.hacbs.container.verifier.MavenUtils.getBuildJdk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.recipes.tools.BuildToolInfo;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.logging.Log;
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

    private final String toolVersions = "sbt:1.8.0,jdk:7;8;11;17;21,maven:3.8.8;3.9.5,ant:1.9.16;1.10.13,gradle:8.4;8.3;8.0.2;7.4.2;7.6.3;7.5.1;6.9.2;5.6.4;4.10.3";

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
        assertThat(info.invocations).isNotEmpty();
        assertEquals("7", info.invocations.get(0).getToolVersion().get("jdk"));
        assertEquals("8", info.invocations.get(1).getToolVersion().get("jdk"));
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
                        cacheBuildInfoLocator),
                "Multiple subdirectories have build files");
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream()
                .noneMatch(r -> LogCollectingTestResource.format(r).matches("Looking for build scripts in.*\\.git.*")));
        assertTrue(logRecords.stream()
                .anyMatch(r -> LogCollectingTestResource.format(r).matches("Looking for build scripts in.*spec.*")));
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
        lookupBuildInfoCommand.tag = "02d3e524110982ec2b420ff8f9126707d483374f";
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
    public void testBuildAnalysisAnt()
            throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        // https://gitlab.ow2.org/asm/asm/-/tree/ASM_6_0?ref_type=tags
        lookupBuildInfoCommand.commit = "016a5134e8bab1d4239fc8dcc47baef11e05d33e";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://gitlab.ow2.org/asm/asm.git", new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        assertThat(info.invocations).isNotEmpty();
        assertTrue(info.invocations.get(0).getCommands().contains("-v"));
        assertTrue(info.invocations.get(0).getTool().contains("ant"));
    }

    @Test
    public void testBuildAnalysisGradleReleaseLegacy()
            throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        // https://gitlab.ow2.org/asm/asm/-/tree/ASM_7_0?ref_type=tags
        lookupBuildInfoCommand.commit = "1f6020a3f17d9d88dfd54a31370e91e3361c216b";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://gitlab.ow2.org/asm/asm.git", new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        assertThat(info.invocations).isNotEmpty();
        assertTrue(info.invocations.get(0).getCommands().contains("uploadArchives"));
        assertTrue(info.invocations.get(0).getCommands().contains("-Prelease"));
    }

    @Test
    public void testBuildAnalysisGradleReleaseCurrent()
            throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        // https://gitlab.ow2.org/asm/asm/-/tree/ASM_9_6?ref_type=tags
        lookupBuildInfoCommand.commit = "85cf1aeb0d08be8446f6efbda962817d2a9707dd";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://gitlab.ow2.org/asm/asm.git", new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        assertThat(info.invocations).isNotEmpty();
        assertTrue(info.invocations.get(0).getCommands().contains("publishToMavenLocal"));
        assertTrue(info.invocations.get(0).getCommands().contains("-Prelease"));
    }

    @Test
    public void testBuildAnalysisOSGIR7CoreSpecFinal()
        throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        // r7-core-spec-final
        lookupBuildInfoCommand.commit = "ac877b9fdaa36e26adb939cf9dd425e77243f449";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://github.com/osgi/osgi.git", new BuildRecipeInfo(),
                                                          cacheBuildInfoLocator);
        assertThat(info.invocations).isNotEmpty();
        assertTrue(info.invocations.get(0).getCommands().contains("publishToMavenLocal"));
        info.invocations.forEach( i -> assertEquals( "4.10.3", i.getToolVersion().get( "gradle" ) ) );
        assertFalse(info.invocations.get(0).getCommands().contains("-Prelease"));
    }

    @Test
    public void testBuildAnalysisMicrometer()
        throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        // 1.12.1
        lookupBuildInfoCommand.commit = "3c39cb09d50ad7e5b94683e9695cc00dba346b13";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://github.com/micrometer-metrics/micrometer.git", new BuildRecipeInfo(),
                                                          cacheBuildInfoLocator);
        assertThat(info.invocations).isNotEmpty();
        assertTrue(info.invocations.get(0).getCommands().contains("publishToMavenLocal"));
        // Ensure we don't have -Prelease as that conflicts with the nebula plugin using release.stage
        assertFalse(info.invocations.get(0).getCommands().contains("-Prelease"));
    }

    @Test
    public void testBuildAnalysisRelaxingDatatype()
        throws Exception {
        LookupBuildInfoCommand lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.mavenContext = new BootstrapMavenContext();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        // 20020414
        lookupBuildInfoCommand.tag = "a83e0896eddbd57a6c8c7afe9cae907199d5108b";
        lookupBuildInfoCommand.commit = "a83e0896eddbd57a6c8c7afe9cae907199d5108b";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://github.com/java-schema-utilities/relaxng-datatype-java.git", new BuildRecipeInfo(),
            cacheBuildInfoLocator);
        assertThat(info.invocations).isNotEmpty();
    }

    @Test
    @Disabled("Cache URL is wrong")
    void testBuildJdkSetsJavaVersion() throws Exception {
        var lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.mavenContext = new BootstrapMavenContext();
        lookupBuildInfoCommand.toolVersions = toolVersions;
        lookupBuildInfoCommand.commit = "7ab205a40853486c1d978e6a7555808b9435407d";
        lookupBuildInfoCommand.cacheUrl = "https://repo1.maven.org/maven2";
        lookupBuildInfoCommand.artifact = "jaxen:jaxen:1.1.6";
        var info = lookupBuildInfoCommand.doBuildAnalysis("https://github.com/codehaus/jaxen.git", new BuildRecipeInfo(),
                cacheBuildInfoLocator);
        var logRecords = LogCollectingTestResource.current().getRecords();
        assertThat(logRecords).flatMap(LogCollectingTestResource::format).contains("Set Java version to [7, 8]");
    }

    @Test
    void testGetBuildJdkFromJarManifest() throws IOException {
        var coordsList = List.of("org.bouncycastle:bcprov-jdk18on:jar:1.71", "com.github.fge:btf:1.2",
                "com.github.mifmif:generex:1.0.2", "xom:xom:1.3.7", "com.carrotsearch:hppc:0.8.1",
                "com.google.code.gson:gson:jar:2.8.9", "com.googlecode.javaewah:JavaEWAH:1.2.3",
                "io.opentelemetry:opentelemetry-sdk-common:jar:1.12.0",
                "org.apache.commons:commons-compress:1.25.0", "wsdl4j:wsdl4j:1.6.3");
        var list = new ArrayList<String>(9);

        for (var coords : coordsList) {
            var optBuildJdk = getBuildJdk("https://repo1.maven.org/maven2", coords);
            if (optBuildJdk.isPresent()) {
                var buildJdk = optBuildJdk.get();
                Log.debugf("%s: %s", coords, buildJdk);
                list.add(buildJdk.version() + ":" + buildJdk.intVersion());
            }
        }

        assertThat(list).containsExactly("1.5:5", "1.6:6", "1.7:7", "1.8:8", "9:9", "11:11", "16:16", "17:17", "21:21");
        var logRecords = LogCollectingTestResource.current().getRecords();
        assertThat(logRecords).flatMap(LogCollectingTestResource::format).contains("Invalid JDK version: 2.3");
    }
}

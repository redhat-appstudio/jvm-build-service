package com.redhat.hacbs.container.analyser.build;

import static com.redhat.hacbs.container.analyser.build.BuildInfo.ANT;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.GRADLE;
import static com.redhat.hacbs.container.analyser.build.BuildInfo.MAVEN;
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
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.recipes.build.BuildRecipeInfo;
import com.redhat.hacbs.recipes.tools.BuildToolInfo;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.logging.Log;
import io.quarkus.test.LogCollectingTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = LogCollectingTestResource.class, restrictToAnnotatedClass = true, initArgs = @ResourceArg(name = LogCollectingTestResource.LEVEL, value = "FINE"))
class LookupBuildInfoCommandTest {
    private static final String TOOL_VERSIONS = "sbt:1.8.0,jdk:7;8;11;17;21,maven:3.8.8;3.9.5,ant:1.9.16;1.10.15,gradle:8.4;8.3;8.0.2;7.4.2;7.6.3;7.5.1;6.9.2;5.6.4;4.10.3";

    private static final String PROXY_URL = "https://repo1.maven.org/maven2";

    private static final String CACHE_PATH = "";

    @BeforeEach
    void clearLogs() {
        LogCollectingTestResource.current().clear();
    }

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
            if (name.equals("maven")) {
                BuildToolInfo a = new BuildToolInfo();
                BuildToolInfo b = new BuildToolInfo();
                BuildToolInfo c = new BuildToolInfo();
                BuildToolInfo d = new BuildToolInfo();
                BuildToolInfo e = new BuildToolInfo();
                BuildToolInfo j = new BuildToolInfo();
                a.setVersion("3.8.8");
                a.setReleaseDate("2023-03-08");
                a.setMinJdkVersion("7");
                b.setVersion("3.9.5");
                b.setReleaseDate("2023-10-04");
                b.setMinJdkVersion("8");
                c.setVersion("3.8.1");
                c.setReleaseDate("2021-04-04");
                c.setMinJdkVersion("7");
                d.setVersion("3.9.0");
                d.setReleaseDate("2023-01-31");
                d.setMinJdkVersion("8");
                e.setVersion("3.3.9");
                e.setReleaseDate("2015-03-18");
                e.setMinJdkVersion("7");
                j.setVersion("11");
                j.setReleaseDate("2018-09-25");
                j.setMinJdkVersion("11");
                j.setMaxJdkVersion("11");
                return List.of(a, b, c, d, j, e);
            } else {
                return List.of();
            }
        }

        @Override
        public List<String> lookupDisabledPlugins(String tool) {
            return List.of();
        }
    };

    private BuildInfo getBuildInfo(String scmUrl, String commit) throws Exception {
        return getBuildInfo(scmUrl, commit, null, null, null, null, null);
    }

    private BuildInfo getBuildInfo(String scmUrl, String commit, String tag) throws Exception {
        return getBuildInfo(scmUrl, commit, tag, null, null, null, null);
    }

    private BuildInfo getBuildInfo(String scmUrl, String commit, String tag, String artifact) throws Exception {
        return getBuildInfo(scmUrl, commit, tag, artifact, PROXY_URL, CACHE_PATH, null);
    }

    private BuildInfo getBuildInfo(String scmUrl, String commit, String tag, String artifact, String cacheUrl, String cachePath, String context) throws Exception {
        var lookupBuildInfoCommand = new LookupBuildInfoCommand();
        lookupBuildInfoCommand.toolVersions = TOOL_VERSIONS;
        lookupBuildInfoCommand.scmUrl = scmUrl;
        lookupBuildInfoCommand.commit = commit;
        lookupBuildInfoCommand.tag = tag;
        lookupBuildInfoCommand.artifact = artifact;
        lookupBuildInfoCommand.cacheUrl = cacheUrl;
        lookupBuildInfoCommand.cachePath = cachePath;
        lookupBuildInfoCommand.context = context;
        lookupBuildInfoCommand.mavenContext = new BootstrapMavenContext();
        return lookupBuildInfoCommand.doBuildAnalysis(scmUrl, new BuildRecipeInfo(), cacheBuildInfoLocator);
    }

    @Test
    void testBuildAnalysis()
            throws Exception {
        var info = getBuildInfo("https://github.com/codehaus/jaxen.git", "7ab205a40853486c1d978e6a7555808b9435407d");
        assertEquals("jaxen", info.getContextPath());
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream()
                .anyMatch(r -> LogCollectingTestResource.format(r)
                        .contains("Unable to locate a build script within")));
        assertThat(info.invocations).hasSize(3);
        assertEquals("8", info.invocations.get(0).getToolVersion().get("jdk"));
        assertEquals("7", info.invocations.get(1).getToolVersion().get("jdk"));
        assertEquals("8", info.invocations.get(2).getToolVersion().get("jdk"));
    }

    @Test
    void testBuildAnalysisWithContext() throws Exception {
        var info = getBuildInfo("https://github.com/codehaus/jaxen.git", "7ab205a40853486c1d978e6a7555808b9435407d", null, null, null, null, "jaxen");
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertNull(info.getContextPath());
        assertTrue(logRecords.stream()
                .noneMatch(r -> LogCollectingTestResource.format(r)
                        .contains("Unable to locate a build script within")));
    }

    @Test
    void testBuildAnalysisFails() {
        assertThrows(RuntimeException.class,
                () -> getBuildInfo("https://github.com/jakartaee/jaf-api.git", "0750e49665855b4b85487aa3780bf0daf09e8e4c"),
                "Multiple subdirectories have build files");
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertThat(logRecords).map(LogCollectingTestResource::format)
                .noneMatch(s -> s.matches("Looking for build scripts in.*\\.git\\.\\.\\."))
                 .anyMatch(s -> s.matches("Looking for build scripts in.*spec.*"));
    }

    @Test
    void testModelResolver1() throws Exception {
        var info = getBuildInfo("https://github.com/wso2/balana", "ea39ccf03d38b65df7aa5153a1bbddc3197b1597");
        assertTrue(info.repositories.contains("https://maven.wso2.org/nexus/content/groups/wso2-public/"));
    }

    @Test
    void testModelResolver2() throws Exception {
        var info = getBuildInfo("https://github.com/jtablesaw/tablesaw", "02d3e524110982ec2b420ff8f9126707d483374f", "02d3e524110982ec2b420ff8f9126707d483374f");
        assertTrue(info.repositories.contains("https://jitpack.io"));
    }

    @Test
    void testModelResolver3() throws Exception {
        var info = getBuildInfo(
                "https://github.com/jvm-build-service-test-data/maven-symlink-in-repo", "7da937230df898d8d1c05522cdfa5e3931921f8f");
        assertTrue(info.repositories.contains("https://repo.maven.apache.org/maven2"));
    }

    @Test
    void testModelResolver4() throws Exception {
        var info = getBuildInfo("https://github.com/javaee/jsonp", "a586e706aea82dc80fb05bdf59f2a25150ee1801");
        assertTrue(info.repositories.contains("https://maven.java.net/content/repositories/snapshots"));
    }

    @Test
    void testBuildAnalysisAnt()
            throws Exception {
        // https://gitlab.ow2.org/asm/asm/-/tree/ASM_6_0?ref_type=tags
        var info = getBuildInfo("https://gitlab.ow2.org/asm/asm.git", "016a5134e8bab1d4239fc8dcc47baef11e05d33e");
        assertThat(info.invocations).isNotEmpty();
        assertTrue(info.invocations.get(0).getCommands().contains("-v"));
        assertTrue(info.invocations.get(0).getTool().contains(ANT));
    }

    @Test
    void testBuildAnalysisGradleReleaseLegacy() throws Exception {
        // https://gitlab.ow2.org/asm/asm/-/tree/ASM_7_0?ref_type=tags
        var info = getBuildInfo("https://gitlab.ow2.org/asm/asm.git", "1f6020a3f17d9d88dfd54a31370e91e3361c216b");
        assertThat(info.invocations).isNotEmpty();
        assertTrue(info.invocations.get(0).getCommands().contains("uploadArchives"));
        assertTrue(info.invocations.get(0).getCommands().contains("-Prelease"));
    }

    @Test
    void testBuildAnalysisGradleReleaseCurrent() throws Exception {
        // https://gitlab.ow2.org/asm/asm/-/tree/ASM_9_6?ref_type=tags
        var info = getBuildInfo("https://gitlab.ow2.org/asm/asm.git", "85cf1aeb0d08be8446f6efbda962817d2a9707dd");
        assertThat(info.invocations).isNotEmpty();
        assertTrue(info.invocations.get(0).getCommands().contains("publishToMavenLocal"));
        assertTrue(info.invocations.get(0).getCommands().contains("-Prelease"));
    }

    @Test
    void testBuildAnalysisOSGIR7CoreSpecFinal() throws Exception {
        // r7-core-spec-final
        var info = getBuildInfo("https://github.com/osgi/osgi.git", "ac877b9fdaa36e26adb939cf9dd425e77243f449");
        assertThat(info.invocations).isNotEmpty();
        assertTrue(info.invocations.get(0).getCommands().contains("publishToMavenLocal"));
        info.invocations.forEach( i -> assertEquals( "4.10.3", i.getToolVersion().get( GRADLE ) ) );
        assertFalse(info.invocations.get(0).getCommands().contains("-Prelease"));
    }

    @Test
    void testBuildAnalysisMicrometer() throws Exception {
        // 1.12.1
        var info = getBuildInfo("https://github.com/micrometer-metrics/micrometer.git", "3c39cb09d50ad7e5b94683e9695cc00dba346b13");
        assertThat(info.invocations).isNotEmpty();
        assertTrue(info.invocations.get(0).getCommands().contains("publishToMavenLocal"));
        // Ensure we don't have -Prelease as that conflicts with the nebula plugin using release.stage
        assertFalse(info.invocations.get(0).getCommands().contains("-Prelease"));
    }

    @Test
    void testBuildAnalysisRelaxngDatatypeJava() throws Exception {
        // 20020414
        var info = getBuildInfo("https://github.com/java-schema-utilities/relaxng-datatype-java.git", "a83e0896eddbd57a6c8c7afe9cae907199d5108b", "a83e0896eddbd57a6c8c7afe9cae907199d5108b");
        assertThat(info.invocations.stream().map(Invocation::getTool).collect(Collectors.toSet())).containsExactlyInAnyOrder(ANT, MAVEN);
    }

    @Test
    public void testBuildAnalysisXmlGraphics() throws Exception {
        // fop-0_94
        var info = getBuildInfo("https://github.com/apache/xmlgraphics-fop.git", "f3450e4bdef0df007f6011e45bb3e213e786d7ea");
        assertThat(info.invocations).isNotEmpty();
    }

    @Test
    void testBuildAnalysisSmallryeConfig() throws Exception {
        // 2.13.3
        var info = getBuildInfo("https://github.com/smallrye/smallrye-config.git", "e6ab5b2b6be149e028ae21be55598cfb0d2b1d37", null, "io.smallrye.config:smallrye-config:2.13.3", "https://repo1.maven.org/maven2", "", null);
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream()
            .anyMatch(r -> LogCollectingTestResource.format(r).matches("Overriding release date.*")));
        assertThat(info.invocations.size()).isEqualTo(2);
        assertTrue(info.invocations.get(0).getToolVersion().get(MAVEN).contains("3.9.5"));
        assertTrue(info.invocations.get(1).getToolVersion().get(MAVEN).contains("3.8.8"));
    }

    @Test
    void testBuildAnalysisIsoRelax() throws Exception {
        var info = getBuildInfo("https://github.com/jvm-build-service-code/iso-relax.git", "bbc253738f4eb82ecc7d7b011b664ef5d53a58c5",null, "com.sun.xml.bind.jaxb:isorelax:20090621");
        assertThat(info.invocations).hasSize(1);
        assertThat(info.invocations.get(0).getToolVersion()).extractingByKey(MAVEN).asString().isEqualTo("3.8.8");
    }

    @Test
    void testBuildAnalysisJavaAnnotations() throws Exception {
        var info = getBuildInfo("https://github.com/jvm-build-service-code/JetBrains-java-annotations.git", "1f1515d801ad49263fbf861673aaa5e3913a625e", null, "org.jetbrains:annotations:13.0");
        assertThat(info.invocations).hasSize(1);
        assertThat(info.invocations.get(0).getToolVersion()).extractingByKey(MAVEN).asString().isEqualTo("3.8.8");
    }

    @Test
    void testBuildAnalysisMultiverse() throws Exception {
        var info = getBuildInfo("https://github.com/pveentjer/Multiverse", "4b41a46a627dd7b4a3dcf8fbf4db5d0a4df84bb4", null, "org.multiverse:multiverse-core:0.7.0");
        assertThat(info.invocations.stream().map(Invocation::getTool).collect(Collectors.toSet())).containsExactlyInAnyOrder(GRADLE, MAVEN);
    }

    @Test
    void testBuildAnalysisJunit5() throws Exception {
        var info = getBuildInfo("https://github.com/junit-team/junit5.git", "5bdb20cf1adc3089c9de4af30ca22c9d96055dc8", null, "org.junit.jupiter:junit-jupiter-api:5.2.0");
        assertThat(LogCollectingTestResource.current().getRecords()).map(LogCollectingTestResource::format).contains("Set Java version to [9, 11] due to preferred version 10");
        assertThat(info.invocations.stream().map(Invocation::getTool).collect(Collectors.toSet())).containsExactlyInAnyOrder(GRADLE);
    }

    @Test
    void testBuildJdkSetsJavaVersion() throws Exception {
        getBuildInfo("https://github.com/codehaus/jaxen.git", "7ab205a40853486c1d978e6a7555808b9435407d", null, "jaxen:jaxen:1.1.6");
        var logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream()
            .anyMatch(r -> LogCollectingTestResource.format(r).contains("Set Java version to [null, 7]")));
        assertTrue(logRecords.stream()
            .anyMatch(r -> LogCollectingTestResource.format(r).contains("Setting build JDK to 1.6 for artifact jaxen:jaxen:1.1.6")));
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
        assertThat(LogCollectingTestResource.current().getRecords()).map(LogCollectingTestResource::format).contains("Invalid JDK version: 2.3");
    }
}

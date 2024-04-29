package com.redhat.hacbs.container.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.LogCollectingTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = LogCollectingTestResource.class, restrictToAnnotatedClass = true, initArgs = @ResourceArg(name = LogCollectingTestResource.LEVEL, value = "FINE"))
class CopyArtifactsCommandTest {
    @BeforeEach
    void clearLogs() {
        LogCollectingTestResource.current().clear();
    }

    void testDeployArtifacts(String dir, String logMessage, String ... artifacts) throws IOException {
        var command = new CopyArtifactsCommand();
        command.sourcePath = Path.of("src/test/resources/copy-artifacts", dir).toAbsolutePath();
        assertThat(command.sourcePath).isDirectory();
        var tempDirectory = Files.createTempDirectory("copy-artifacts-test");
        assertThat(tempDirectory).isDirectory();
        command.deployPath = tempDirectory.resolve("artifacts");
        assertThat(command.deployPath).doesNotExist();
        command.run();

        if (!logMessage.isEmpty()) {
            assertThat(LogCollectingTestResource.current().getRecords()).map(LogCollectingTestResource::format).contains(logMessage);
        }

        try (var stream = Files.walk(command.deployPath)) {
            assertThat(stream).filteredOn(Files::isRegularFile).map(path -> path.getFileName().toString()).containsExactlyInAnyOrder(artifacts);
        }
    }

    @Test
    void testAsm() throws IOException {
        testDeployArtifacts("asm", "Skipping POM asm-all.pom with invalid version @product.artifact@", "asm-5.0.3.jar", "asm-5.0.3.pom", "asm-all-5.0.3.jar", "asm-all-5.0.3.pom",
            "asm-analysis-5.0.3.jar", "asm-analysis-5.0.3.pom", "asm-commons-5.0.3.jar", "asm-commons-5.0.3.pom",
            "asm-debug-all-5.0.3.jar", "asm-debug-all-5.0.3.pom", "asm-parent-5.0.3.pom", "asm-tree-5.0.3.jar",
            "asm-tree-5.0.3.pom", "asm-util-5.0.3.jar", "asm-util-5.0.3.pom", "asm-xml-5.0.3.jar", "asm-xml-5.0.3.pom");
    }

    @Test
    void testBeanshell() throws IOException {
        testDeployArtifacts("beanshell", "", "bsh-2.0b6.jar", "bsh-2.0b6.pom");
    }

    @Test
    void testIcu4J() throws IOException {
        testDeployArtifacts("icu4j", "", "icu4j-71.1.jar", "icu4j-71.1.pom", "icu4j-charset-71.1.jar", "icu4j-charset-71.1.pom", "icu4j-localespi-71.1.jar", "icu4j-localespi-71.1.pom");
    }

    @Test
    void testJdom() throws IOException {
        testDeployArtifacts("jdom", "Skipping POM maven.pom with invalid version @version@", "jdom2-2.x-2024.03.01.10.00.jar", "jdom2-2.x-2024.03.01.10.00.pom");
    }

    @Test
    void testLombok() throws IOException {
        testDeployArtifacts("lombok", "Skipping POM pom.xml with invalid version 1.0-SNAPSHOT", "lombok-1.18.24.jar", "lombok-1.18.24.pom");
    }

    @Test
    void testLz4Java() throws IOException {
        testDeployArtifacts("lz4-java", "", "lz4-java-1.8.0.jar", "lz4-java-1.8.0.pom", "lz4-pure-java-1.8.0.jar", "lz4-pure-java-1.8.0.pom");
    }

    @Test
    void testTomcat() throws IOException {
        testDeployArtifacts("tomcat", "Skipping POM tomcat-annotations-api.pom with invalid version @MAVEN.DEPLOY.VERSION@", "tomcat-annotations-api-10.1.19.jar", "tomcat-annotations-api-10.1.19.pom", "tomcat-api-10.1.19.jar", "tomcat-api-10.1.19.pom", "tomcat-catalina-10.1.19.jar", "tomcat-catalina-10.1.19.pom", "tomcat-catalina-ant-10.1.19.jar", "tomcat-catalina-ant-10.1.19.pom", "tomcat-catalina-ha-10.1.19.jar", "tomcat-catalina-ha-10.1.19.pom", "tomcat-coyote-10.1.19.jar", "tomcat-coyote-10.1.19.pom", "tomcat-dbcp-10.1.19.jar", "tomcat-dbcp-10.1.19.pom", "tomcat-el-api-10.1.19.jar", "tomcat-el-api-10.1.19.pom", "tomcat-embed-core-10.1.19.jar", "tomcat-embed-core-10.1.19.pom", "tomcat-embed-el-10.1.19.jar", "tomcat-embed-el-10.1.19.pom", "tomcat-embed-jasper-10.1.19.jar", "tomcat-embed-jasper-10.1.19.pom", "tomcat-embed-programmatic-10.1.19.jar", "tomcat-embed-programmatic-10.1.19.pom", "tomcat-embed-websocket-10.1.19.jar", "tomcat-embed-websocket-10.1.19.pom", "tomcat-jasper-10.1.19.jar", "tomcat-jasper-10.1.19.pom", "tomcat-jasper-el-10.1.19.jar", "tomcat-jasper-el-10.1.19.pom", "tomcat-jaspic-api-10.1.19.jar", "tomcat-jaspic-api-10.1.19.pom", "tomcat-jdbc-10.1.19.jar", "tomcat-jdbc-10.1.19.pom", "tomcat-jni-10.1.19.jar", "tomcat-jni-10.1.19.pom", "tomcat-jsp-api-10.1.19.jar", "tomcat-jsp-api-10.1.19.pom", "tomcat-juli-10.1.19.jar", "tomcat-juli-10.1.19.pom", "tomcat-servlet-api-10.1.19.jar", "tomcat-servlet-api-10.1.19.pom", "tomcat-ssi-10.1.19.jar", "tomcat-ssi-10.1.19.pom", "tomcat-storeconfig-10.1.19.jar", "tomcat-storeconfig-10.1.19.pom", "tomcat-tribes-10.1.19.jar", "tomcat-tribes-10.1.19.pom", "tomcat-util-10.1.19.jar", "tomcat-util-10.1.19.pom", "tomcat-util-scan-10.1.19.jar", "tomcat-util-scan-10.1.19.pom", "tomcat-websocket-10.1.19.jar", "tomcat-websocket-10.1.19.pom", "tomcat-websocket-api-10.1.19.jar", "tomcat-websocket-api-10.1.19.pom", "tomcat-websocket-client-api-10.1.19.jar", "tomcat-websocket-client-api-10.1.19.pom");
    }
}

package com.redhat.hacbs.container.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.quarkus.test.junit.main.QuarkusMainTest;

@QuarkusMainTest
public class MavenPreprocessorTestCase extends AbstractPreprocessorTestCase {

    public static List<Path> factory() throws IOException {
        return getbuilds("mavenbuilds");
    }

    @Override
    public String getCommand() {
        return "maven-prepare";
    }
}

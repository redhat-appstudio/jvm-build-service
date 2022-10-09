package com.redhat.hacbs.container.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.quarkus.test.junit.main.QuarkusMainTest;

@QuarkusMainTest
public class GradlePreprocessorTestCase extends AbstractPreprocessorTestCase {

    public static List<Path> factory() throws IOException {
        return getbuilds("gradlebuilds");
    }

    @Override
    public String getCommand() {
        return "gradle-prepare";
    }
}

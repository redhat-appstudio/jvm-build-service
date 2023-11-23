package com.redhat.hacbs.container.build;

import static com.redhat.hacbs.container.analyser.build.BuildInfo.MAVEN;
import static com.redhat.hacbs.container.analyser.build.InvocationBuilderTestCase.buildInfoLocator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.quarkus.test.junit.main.QuarkusMainTest;

@QuarkusMainTest
public class MavenPreprocessorTestCase extends AbstractPreprocessorTestCase {

    public static List<Path> factory() throws IOException {
        return getbuilds("mavenbuilds");
    }

    @Override
    public List<String> getCommand() {
        var args = buildInfoLocator.lookupDisabledPlugins(MAVEN);
        var command = new ArrayList<String>(1 + 2 * args.size());
        command.add("maven-prepare");
        args.forEach(arg -> {
            command.add("-dp");
            command.add(arg);
        });
        return command;
    }
}

package com.redhat.hacbs.container.build.preprocessor.gradle;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import com.redhat.hacbs.container.build.preprocessor.AbstractPreprocessor;

import io.quarkus.logging.Log;
import picocli.CommandLine;

/**
 * A simple preprocessor that attempts to get gradle build files into a state where they can be built.
 * <p>
 * At present it just sets up the init script
 */
@CommandLine.Command(name = "gradle-prepare")
public class GradlePrepareCommand extends AbstractPreprocessor {

    public static final String[] INIT_SCRIPTS = {
            "disable-plugins.gradle",
            "info.gradle",
            "javadoc.gradle",
            "repositories.gradle",
            "uploadArchives.gradle",
            "version.gradle"
    };

    @Override
    public void run() {
        try {
            setupInitScripts();
            Files.walkFileTree(buildRoot, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();
                    if (fileName.equals("build.gradle")) {
                        handleBuildGradle(file);
                    } else if (fileName.equals("build.gradle.kts")) {
                        handleBuildKotlin(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

            });
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }

    }

    private void setupInitScripts() throws IOException, URISyntaxException {
        var initDir = buildRoot.resolve(".hacbs-init");
        Files.createDirectories(initDir);
        for (var initScript : INIT_SCRIPTS) {
            var init = initDir.resolve(initScript);
            try (var in = getClass().getClassLoader().getResourceAsStream("gradle/" + initScript)) {
                Files.copy(in, init);

                if ("disable-plugins.gradle".equals(init.getFileName().toString())) {
                    Files.writeString(init,
                            Files.readString(init).replace("@DISABLED_PLUGINS@",
                                    String.join(",", disabledPlugins != null ? disabledPlugins : List.of())));
                }

                Log.infof("Wrote init script to %s", init.toAbsolutePath());
            }
        }
    }

    private void handleBuildKotlin(Path file) throws IOException {
        //TODO
    }

    private void handleBuildGradle(Path file) throws IOException {
        //TODO

    }
}

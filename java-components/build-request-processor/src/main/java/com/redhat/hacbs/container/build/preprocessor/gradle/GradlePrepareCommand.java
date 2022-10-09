package com.redhat.hacbs.container.build.preprocessor.gradle;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import com.redhat.hacbs.container.build.preprocessor.AbstractPreprocessor;

import picocli.CommandLine;

/**
 * A simple preprocessor that attempts to get gradle build files into a state where GME can work on them.
 *
 * At present this is just a shell as we are experimenting with other options, but it will likely be needed in some form
 * so it is here to keep the maven/gradle pipelines comprable.
 */
@CommandLine.Command(name = "gradle-prepare")
public class GradlePrepareCommand extends AbstractPreprocessor {

    public static final String REPOSITORIES_REGEX = "(repositories|uploadArchives)\\s*\\{";

    @Override
    public void run() {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private void handleBuildKotlin(Path file) throws IOException {
        //TODO
    }

    private void handleBuildGradle(Path file) throws IOException {
        //TODO

    }
}

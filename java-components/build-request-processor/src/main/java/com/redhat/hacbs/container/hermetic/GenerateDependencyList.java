package com.redhat.hacbs.container.hermetic;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Singleton;

import io.quarkus.arc.Unremovable;
import picocli.CommandLine;

@CommandLine.Command(name = "generate-dependency-list")
@Singleton
@Unremovable
public class GenerateDependencyList implements Runnable {

    private static final Pattern MAVEN_PATTERN = Pattern.compile("^(.*?)/([^/]*?)/([^/]*?)/\\2-\\3-?([^/\\.]*?)\\.([^/\\.]*)$");

    @CommandLine.Option(names = "--m2-dir")
    Optional<Path> m2Dir;

    @CommandLine.Option(names = "--gradle-dir")
    Optional<Path> gradleDir;

    @CommandLine.Option(names = "--dep-file", required = true)
    Path output;

    @Override
    public void run() {
        var m2 = m2Dir.orElse(Path.of(System.getProperty("user.home") + File.separator + ".m2/repository"));
        var gradle = gradleDir.orElse(Path.of(System.getProperty("user.home") + File.separator + ".gradle"));
        try {
            List<String> deps = new ArrayList<>();
            deps.addAll(collectMavenDependencies(m2));
            deps.addAll(collectGradleDependencies(gradle));
            Collections.sort(deps);
            Files.write(output, deps);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private Collection<String> collectGradleDependencies(Path gradle) {
        var ret = new ArrayList<String>();
        return ret;
    }

    private Collection<String> collectMavenDependencies(Path repo) throws IOException {
        var ret = new ArrayList<String>();
        Files.walkFileTree(repo, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                var repos = dir.resolve("_remote.repositories");
                if (Files.exists(repos)) {
                    var lines = Files.readAllLines(repos);
                    for (var i : lines) {
                        i = i.trim();
                        if (i.startsWith("#") || i.endsWith(">=")) {
                            continue;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = repo.relativize(file).toString();

                Matcher matcher = MAVEN_PATTERN.matcher(relative);
                boolean matches = matcher.matches();
                if (matches) {
                    ret.add(matcher.group(1).replace("/", ".") + ":" + matcher.group(2) + ":" + matcher.group(3) + ":"
                            + matcher.group(4) + ":" + matcher.group(5));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return ret;
    }
}

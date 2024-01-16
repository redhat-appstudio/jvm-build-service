package com.redhat.hacbs.container.build;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.redhat.hacbs.recipes.util.FileUtil;

import io.quarkus.test.junit.main.QuarkusMainLauncher;

public abstract class AbstractPreprocessorTestCase {

    public static final String EXPECTED = ".expected";
    static Path tempDir;

    public static List<Path> getbuilds(String directory) throws IOException {
        Path path = Paths.get("src/test/resources/" + directory).toAbsolutePath();
        var temp = Files.createTempDirectory("hacbs-test");
        Files.walkFileTree(path, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(temp.resolve(path.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, temp.resolve(path.relativize(file)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
        List<Path> ret = new ArrayList<>();
        try (var s = Files.list(temp)) {
            s.forEach(new Consumer<Path>() {
                @Override
                public void accept(Path path) {
                    ret.add(path);
                }
            });
        }
        return ret;
    }

    @AfterAll
    public static void delete() {
        if (tempDir != null) {
            FileUtil.deleteRecursive(tempDir);
        }
    }

    public abstract List<String> getCommand();

    @ParameterizedTest()
    @MethodSource("factory")
    public void testPreprocessor(Path path, QuarkusMainLauncher launcher) throws IOException {
        System.out.println(path);
        var command = new ArrayList<>(getCommand());
        command.add(path.toString());
        var result = launcher.launch(command.toArray(new String[0]));
        assertThat(result.exitCode()).isZero();
        AtomicInteger count = new AtomicInteger();
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (name.endsWith(EXPECTED)) {
                    count.incrementAndGet();
                    Path modified = file.getParent().resolve(name.substring(0, name.length() - EXPECTED.length()));
                    assertThat(modified).hasSameTextualContentAs(file, UTF_8);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertThat(count).hasPositiveValue();
    }
}

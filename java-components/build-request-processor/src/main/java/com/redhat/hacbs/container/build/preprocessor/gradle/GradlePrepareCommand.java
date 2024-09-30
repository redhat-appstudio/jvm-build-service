package com.redhat.hacbs.container.build.preprocessor.gradle;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;

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

    private final String SPRING = ".*(id|classpath).*io\\.spring\\.ge\\.conventions.*0\\.0\\.\\b([1-9]|1[0-3])\\b.*";

    public static final String[] INIT_SCRIPTS = {
            "disable-plugins.gradle",
            "info.gradle",
            "javadoc.gradle",
            "repositories.gradle",
            "test.gradle",
            "uploadArchives.gradle",
            "version.gradle"
    };

    public GradlePrepareCommand() {
        type = ToolType.GRADLE;
    }

    @Override
    public void run() {
        try {
            super.run();
            setupInitScripts();
            Files.walkFileTree(buildRoot, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".gradle")) {
                        handleBuildGradle(file);
                    } else if (fileName.endsWith(".gradle.kts")) {
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
                Files.copy(in, init, StandardCopyOption.REPLACE_EXISTING);

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
        modifySpringEnterprisePlugin(file);
    }

    private void handleBuildGradle(Path file) throws IOException {
        modifySpringEnterprisePlugin(file);
    }


    public void modifySpringEnterprisePlugin(Path file) throws IOException {
        // Plugins can be applied using the id pattern or added using
        // the legacy classpath pattern e.g.
        // id("...")
        // id "..."
        // classpath("org.ysb33r.gradle:gradletest:3.0.0-alpha.5")

        // For the https://github.com/spring-io/gradle-enterprise-conventions plugin
        // only 5,6,7,13,14 and 15 are on Central.
        // 4,8,9,10,11,12 are on defunct Spring repositories.
        List<String> lines = FileUtils.readLines(file.toFile(), Charset.defaultCharset());
        Pattern pattern = Pattern.compile(SPRING);
        for (int i=0; i<lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = pattern.matcher(line);
            if (m.matches()) {
                Log.infof("Replacing '%s' in '%s' with version 15", m.group(2), line);
                line = line.replace(m.group(2), "15");
                lines.set(i, line);
            }
        }
        FileUtils.writeLines(file.toFile(), lines);
    }
}

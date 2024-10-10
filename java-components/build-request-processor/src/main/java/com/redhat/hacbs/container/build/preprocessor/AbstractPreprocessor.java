package com.redhat.hacbs.container.build.preprocessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import io.quarkus.logging.Log;
import picocli.CommandLine;

/**
 * We keep all the options the same between maven, gradle, sbt and ant for now to keep the pipeline setup simpler.
 * Some of these may be ignored by different processors
 */
public abstract class AbstractPreprocessor implements Runnable {

    @CommandLine.Parameters(description = "The directory to process")
    protected Path buildRoot;

    @CommandLine.Option(names = { "-dp", "--disabled-plugins" }, paramLabel = "<plugin>", description = "The plugin to disable", split=",")
    protected List<String> disabledPlugins;

    @CommandLine.Option(names = "--recipe-image", required = true)
    String recipeImage;

    @CommandLine.Option(names = "--request-processor-image", required = true)
    String buildRequestProcessorImage;

    @CommandLine.Option(names = "--java-home", required = true)
    String javaHome;

    @CommandLine.Option(names = "--build-tool-version", required = true)
    String buildToolVersion;

    protected enum ToolType {
        ANT,
        GRADLE,
        MAVEN,
        SBT
    }

    protected ToolType type;

    @Override
    public void run() {

        Log.warnf("### Using tool {} with version {} and javaHome {}", type, buildToolVersion, javaHome);

        Path jbsDirectory = Path.of(buildRoot.toString(), ".jbs");
        //noinspection ResultOfMethodCallIgnored
        jbsDirectory.toFile().mkdirs();

        String containerFile = """
            FROM %s
            USER 0
            WORKDIR /var/workdir
            RUN mkdir -p /var/workdir/software/settings /original-content/marker
            # CACHE_URL is deprecated.
            ARG CACHE_URL=""
            ENV CACHE_URL=$CACHE_URL
            COPY .jbs/run-build.sh /var/workdir
            COPY . /var/workdir/workspace/source/
            RUN /var/workdir/run-build.sh
            """.formatted(recipeImage);

        // TODO: This is a bit of a hack but as Ant doesn't deploy and the previous implementation relied upon using the
        //     BuildRequestProcessorImage we need to modify the Containerfile. In future the ant-build.sh should probably
        //     encapsulate this.
        if (type == ToolType.ANT) {
            // Don't think we need to mess with keystore as copy-artifacts is simply calling copy commands.
            containerFile +=
                """
                    FROM %s AS build-request-processor
                    USER 0
                    WORKDIR /var/workdir
                    COPY --from=0 /var/workdir/ /var/workdir/
                    RUN /opt/jboss/container/java/run/run-java.sh copy-artifacts --source-path=/var/workdir/workspace/source --deploy-path=/var/workdir/workspace/artifacts
                    FROM scratch
                    COPY --from=1 /var/workdir/workspace/artifacts /
                    """.formatted(buildRequestProcessorImage);
        } else {
            containerFile +=
                """
                    FROM scratch
                    COPY --from=0 /var/workdir/workspace/artifacts /
                    """;
        }
        try {
            Files.writeString(Paths.get(jbsDirectory.toString(), "Containerfile"), containerFile);
        } catch (IOException e) {
            Log.errorf("Unable to write Containerfile", e);
            throw new RuntimeException(e);
        }
    }
}

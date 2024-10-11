package com.redhat.hacbs.container.build.preprocessor;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;

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

    /**
     * Equivalent to <code>$(workspaces.source.path)/source</code>
     */
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
        SBT;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    protected ToolType type;

    @Override
    public void run() {

        Log.warnf("### Using tool %s with version %s and javaHome %s", type, buildToolVersion, javaHome);
        Log.warnf("### ENV %s", System.getenv("jvm-build-service"));
        Log.warnf("### BUILD_SCRIPT %s", System.getenv("BUILD_SCRIPT"));

        Path jbsDirectory = Path.of(buildRoot.toString(), ".jbs");
        //noinspection ResultOfMethodCallIgnored
        jbsDirectory.toFile().mkdirs();

        String runBuild = """
            #!/usr/bin/env bash
            set -o verbose
            set -eu
            set -o pipefail

            export LANG="en_US.UTF-8"
            export LC_ALL="en_US.UTF-8"
            export JAVA_HOME=%s
            # This might get overridden by the tool home configuration below. This is
            # useful if Gradle/Ant also requires Maven configured.
            export MAVEN_HOME=/opt/maven/3.8.8
            export %s_HOME=/opt/%s/%s

            cd %s
            mkdir -p ../logs ../packages

            if [ ! -z ${JAVA_HOME+x} ]; then
                echo "JAVA_HOME:$JAVA_HOME"
                PATH="${JAVA_HOME}/bin:$PATH"
            fi

            if [ ! -z ${MAVEN_HOME+x} ]; then
                echo "MAVEN_HOME:$MAVEN_HOME"
                PATH="${MAVEN_HOME}/bin:$PATH"

                if [ ! -d "${MAVEN_HOME}" ]; then
                    echo "Maven home directory not found at ${MAVEN_HOME}" >&2
                    exit 1
                fi
            fi

            if [ ! -z ${GRADLE_HOME+x} ]; then
                echo "GRADLE_HOME:$GRADLE_HOME"
                PATH="${GRADLE_HOME}/bin:$PATH"

                if [ ! -d "${GRADLE_HOME}" ]; then
                    echo "Gradle home directory not found at ${GRADLE_HOME}" >&2
                    exit 1
                fi
            fi

            if [ ! -z ${ANT_HOME+x} ]; then
                echo "ANT_HOME:$ANT_HOME"
                PATH="${ANT_HOME}/bin:$PATH"

                if [ ! -d "${ANT_HOME}" ]; then
                    echo "Ant home directory not found at ${ANT_HOME}" >&2
                    exit 1
                fi
            fi

            if [ ! -z ${SBT_DIST+x} ]; then
                echo "SBT_DIST:$SBT_DIST"
                PATH="${SBT_DIST}/bin:$PATH"

                if [ ! -d "${SBT_DIST}" ]; then
                    echo "SBT home directory not found at ${SBT_DIST}" >&2
                    exit 1
                fi
            fi
            echo "PATH:$PATH"

            #fix this when we no longer need to run as root
            export HOME=/root


            """.formatted(javaHome, type.name(), type, buildToolVersion, buildRoot);

        Log.warnf("### runBuild is %s", runBuild);

        String containerFile = """
            FROM %s
            USER 0
            WORKDIR /var/workdir
            """.formatted(recipeImage);

        // This block is only needed for running inside JBS
        if (isNotEmpty(System.getenv("jvm-build-service"))) {
            //RUN mkdir -p /var/workdir/software/settings /original-content/marker
            containerFile += """

            ARG CACHE_URL=""
            ENV CACHE_URL=$CACHE_URL
            """;
        }
        containerFile += """
            COPY .jbs/run-build.sh /var/workdir
            COPY . /var/workdir/workspace/source/
            RUN /var/workdir/run-build.sh
            """;

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

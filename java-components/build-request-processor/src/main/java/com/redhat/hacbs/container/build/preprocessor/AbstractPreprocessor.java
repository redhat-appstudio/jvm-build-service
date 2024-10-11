package com.redhat.hacbs.container.build.preprocessor;

import static org.apache.commons.lang3.StringUtils.isEmpty;
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

    @CommandLine.Option(names = "--java-version", required = true)
    String javaVersion;

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
        Path workspaceSource = buildRoot.getParent();
        Path jbsDirectory = Path.of(buildRoot.toString(), ".jbs");
        //noinspection ResultOfMethodCallIgnored
        jbsDirectory.toFile().mkdirs();

        String buildScript = System.getenv("BUILD_SCRIPT");
        Log.warnf("### BUILD_SCRIPT %s", buildScript);
        if (isEmpty(buildScript)) {
            Log.errorf("Unable to find BUILD_SCRIPT in environment");
            throw new RuntimeException("No BUILD_SCRIPT found");
        }

        Log.warnf("### WorkspaceSource %s", workspaceSource);
        Log.warnf("### Using tool %s with version %s and javaHome %s", type, buildToolVersion, javaVersion);
        Log.warnf("### ENV %s", System.getenv("jvm-build-service"));

        String javaHome = "";
        if (javaVersion.equals("7") || javaVersion.equals("8")) {
            javaHome = "/lib/jvm/java-1." + javaVersion + ".0";
        } else {
            javaHome = "/lib/jvm/java-" + javaVersion;
        }


        // TODO: Rename CACHE_URL to PROXY_URL to cover both Indy and JBS use-cases
        String runBuild = """
            #!/usr/bin/env bash
            set -o verbose
            set -eu
            set -o pipefail

            #fix this when we no longer need to run as root
            export HOME=/root

            export LANG="en_US.UTF-8"
            export LC_ALL="en_US.UTF-8"
            export JAVA_HOME=%s
            # This might get overridden by the tool home configuration below. This is
            # useful if Gradle/Ant also requires Maven configured.
            export MAVEN_HOME=/opt/maven/3.8.8
            # If we run out of memory we want the JVM to die with error code 134
            export MAVEN_OPTS="-XX:+CrashOnOutOfMemoryError"
            # If we run out of memory we want the JVM to die with error code 134
            export JAVA_OPTS="-XX:+CrashOnOutOfMemoryError"
            export %s_HOME=/opt/%s/%s

            mkdir -p /var/workdir/workspace/artifacts /var/workdir/workspace/logs /var/workdir/workspace/packages /var/workdir/software/settings ${HOME}/.sbt/1.0
            cd %s

            if [ ! -z ${JAVA_HOME+x} ]; then
                echo "JAVA_HOME:$JAVA_HOME"
                PATH="${JAVA_HOME}/bin:$PATH"
            fi

            if [ ! -z ${MAVEN_HOME+x} ]; then
            """.formatted(javaHome, type.name(), type, buildToolVersion, buildRoot);

        runBuild += getMavenSetup();

        runBuild += """
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

                if [ ! z ${CACHE_URL+x} ]; then
                    cat > ivysettings.xml << EOF
                <ivysettings>
                    <property name="cache-url" value="${CACHE_URL}"/>
                    <property name="default-pattern" value="[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"/>
                    <property name="local-pattern" value="\\${user.home}/.m2/repository/[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"/>
                    <settings defaultResolver="defaultChain"/>
                    <resolvers>
                        <ibiblio name="default" root="\\${cache-url}" pattern="\\${default-pattern}" m2compatible="true"/>
                        <filesystem name="local" m2compatible="true">
                            <artifact pattern="\\${local-pattern}"/>
                            <ivy pattern="\\${local-pattern}"/>
                        </filesystem>
                        <chain name="defaultChain">
                            <resolver ref="local"/>
                            <resolver ref="default"/>
                        </chain>
                    </resolvers>
                </ivysettings>
                EOF
                fi

            fi

            if [ ! -z ${SBT_DIST+x} ]; then
                echo "SBT_DIST:$SBT_DIST"
                PATH="${SBT_DIST}/bin:$PATH"

                if [ ! -d "${SBT_DIST}" ]; then
                    echo "SBT home directory not found at ${SBT_DIST}" >&2
                    exit 1
                fi

                if [ ! z ${CACHE_URL+x} ]; then
                    cat > "$HOME/.sbt/repositories" <<EOF
                    [repositories]
                      local
                      my-maven-proxy-releases: ${CACHE_URL}
                    EOF
                fi
                # TODO: we may need .allowInsecureProtocols here for minikube based tests that don't have access to SSL
                cat >"$HOME/.sbt/1.0/global.sbt" <<EOF
                publishTo := Some(("MavenRepo" at s"file:%s/artifacts")),
                EOF


            fi
            echo "PATH:$PATH"

            """.formatted(workspaceSource);

        Log.warnf("### runBuild is %s", runBuild);


        runBuild += buildScript;



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
            Path runBuildSh = Paths.get(jbsDirectory.toString(), "run-build.sh");
            Files.writeString(runBuildSh, runBuild);
            runBuildSh.toFile().setExecutable(true);
            Files.writeString(Paths.get(jbsDirectory.toString(), "Containerfile"), containerFile);
        } catch (IOException e) {
            Log.errorf("Unable to write Containerfile", e);
            throw new RuntimeException(e);
        }
    }


    private String getMavenSetup() {
        return """
               echo "MAVEN_HOME:$MAVEN_HOME"
                PATH="${MAVEN_HOME}/bin:$PATH"

                if [ ! -d "${MAVEN_HOME}" ]; then
                    echo "Maven home directory not found at ${MAVEN_HOME}" >&2
                    exit 1
                fi

                if [ ! z ${CACHE_URL+x} ]; then
                    cat >"/var/workdir/software/settings"/settings.xml <<EOF
                    <settings>
                      <mirrors>
                        <mirror>
                          <id>mirror.default</id>
                          <url>${CACHE_URL}</url>
                          <mirrorOf>*</mirrorOf>
                        </mirror>
                      </mirrors>
                EOF
                else
                    cat >"/var/workdir/software/settings"/settings.xml <<EOF
                    <settings>
                EOF
                fi
                cat >>"/var/workdir/software/settings"/settings.xml <<EOF
                  <!-- Off by default, but allows a secondary Maven build to use results of prior (e.g. Gradle) deployment -->
                  <profiles>
                    <profile>
                      <id>secondary</id>
                      <activeByDefault>true</activeByDefault>
                      </activation>
                      <repositories>
                        <repository>
                          <id>artifacts</id>
                          <url>file:///var/workdir/workspace/artifacts</url>
                          <releases>
                            <enabled>true</enabled>
                            <checksumPolicy>ignore</checksumPolicy>
                          </releases>
                        </repository>
                      </repositories>
                      <pluginRepositories>
                        <pluginRepository>
                          <id>artifacts</id>
                          <url>file:///var/workdir/workspace/artifacts</url>
                          <releases>
                            <enabled>true</enabled>
                            <checksumPolicy>ignore</checksumPolicy>
                          </releases>
                        </pluginRepository>
                      </pluginRepositories>
                    </profile>
                  </profiles>
                </settings>
                EOF


                TOOLCHAINS_XML="/var/workdir/software/settings"/toolchains.xml

                cat >"$TOOLCHAINS_XML" <<EOF
                <?xml version="1.0" encoding="UTF-8"?>
                <toolchains>
                EOF

                if [ "%s" = "7" ]; then
                    JAVA_VERSIONS="7:1.7.0 8:1.8.0 11:11"
                else
                    JAVA_VERSIONS="8:1.8.0 9:11 11:11 17:17 21:21 22:22"
                fi

                for i in $JAVA_VERSIONS; do
                    version=$(echo $i | cut -d : -f 1)
                    home=$(echo $i | cut -d : -f 2)
                    cat >>"$TOOLCHAINS_XML" <<EOF
                  <toolchain>
                    <type>jdk</type>
                    <provides>
                      <version>$version</version>
                    </provides>
                    <configuration>
                      <jdkHome>/usr/lib/jvm/java-$home-openjdk</jdkHome>
                    </configuration>
                  </toolchain>
                EOF
                done

                cat >>"$TOOLCHAINS_XML" <<EOF
                </toolchains>
                EOF
        """.formatted(javaVersion);
    }
}

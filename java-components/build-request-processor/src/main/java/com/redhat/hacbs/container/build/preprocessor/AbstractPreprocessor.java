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
     * $(workspaces.source.path) = /var/workdir/workspace
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

    /**
     * This section creates two files within a <code>.jbs</code> subdirectory. The Containerfile is used
     * by Konflux to initiate a build and the <code>run-build.sh</code> contains generic setup
     * ( e.g. PATHs, directories, Maven Settings, etc) to which is appended the user build script for the main
     * build.
     */
    @Override
    public void run() {
        Path jbsDirectory = Path.of(buildRoot.toString(), ".jbs");
        //noinspection ResultOfMethodCallIgnored
        jbsDirectory.toFile().mkdirs();

        String buildScript = System.getenv("BUILD_SCRIPT");
        if (isEmpty(buildScript)) {
            Log.errorf("Unable to find BUILD_SCRIPT in environment");
        }

        Log.warnf("### Using tool %s with version %s and javaHome %s", type, buildToolVersion, javaVersion);

        String javaHome;
        if (javaVersion.equals("7") || javaVersion.equals("8")) {
            javaHome = "/lib/jvm/java-1." + javaVersion + ".0";
        } else {
            javaHome = "/lib/jvm/java-" + javaVersion;
        }

        String runBuild = """
            #!/usr/bin/env bash
            set -o verbose
            set -o pipefail
            set -e

            export http_proxy=http://localhost:8080
            export https_proxy=${http_proxy}
            export HTTP_PROXY=${http_proxy}
            export HTTPS_PROXY=${http_proxy}
            export ANT_OPTS="-Dhttp.proxyHost=localhost -Dhttp.proxyPort=8080"
            #fix this when we no longer need to run as root
            export HOME=${HOME:=/root}
            # Custom base working directory.
            export JBS_WORKDIR=${JBS_WORKDIR:=/var/workdir/workspace}

            export LANG="en_US.UTF-8"
            export LC_ALL="en_US.UTF-8"
            export JAVA_HOME=${JAVA_HOME:=%s}
            # If we run out of memory we want the JVM to die with error code 134
            export MAVEN_OPTS="-XX:+CrashOnOutOfMemoryError"
            # If we run out of memory we want the JVM to die with error code 134
            export JAVA_OPTS="-XX:+CrashOnOutOfMemoryError"
            export %s_HOME=${%s_HOME:=/opt/%s/%s}
            # This might get overridden by the tool home configuration above. This is
            # useful if Gradle/Ant also requires Maven configured.
            export MAVEN_HOME=${MAVEN_HOME:=/opt/maven/3.8.8}
            export GRADLE_USER_HOME="${JBS_WORKDIR}/software/settings/.gradle"

            mkdir -p ${JBS_WORKDIR}/logs ${JBS_WORKDIR}/packages ${HOME}/.sbt/1.0 ${GRADLE_USER_HOME} ${HOME}/.m2
            cd ${JBS_WORKDIR}/source

            if [ -n "${JAVA_HOME}" ]; then
                echo "JAVA_HOME:$JAVA_HOME"
                PATH="${JAVA_HOME}/bin:$PATH"
            fi

            if [ -n "${MAVEN_HOME}" ]; then
            """.formatted(javaHome, type.name(), type.name(), type, buildToolVersion);

        runBuild += getMavenSetup();

        runBuild += """
            fi

            if [ -n "${GRADLE_HOME}" ]; then
            """;

        runBuild += getGradleSetup();

        runBuild += """
            fi

            if [ -n "${ANT_HOME}" ]; then
            """;

        runBuild += getAntSetup();

        runBuild += """
            fi

            if [ -n "${SBT_HOME}" ]; then
            """;

        runBuild += getSbtSetup();

        runBuild += """
            fi
            echo "PATH:$PATH"

            update-ca-trust

            # Go through certificates and insert them into the cacerts
            for cert in $(find /etc/pki/ca-trust/source/anchors -type f); do
              echo "Inserting $cert into java cacerts"
              keytool -import -alias $(basename $cert)-ca \\
                -file $cert \\
                -keystore /etc/pki/java/cacerts \\
                -storepass changeit --noprompt
            done

            # End of generic build script

            echo "Building the project ..."
            """;

        if (isNotEmpty(buildScript)) {
            // Now add in the build script from either JBS or PNC. This might contain e.g. "mvn -Pfoo install"
            runBuild += buildScript;
        }
        Log.debugf("### runBuild is\n%s", runBuild);

        try {
            Path runBuildSh = Paths.get(jbsDirectory.toString(), "run-build.sh");
            Files.writeString(runBuildSh, runBuild);
            //noinspection ResultOfMethodCallIgnored
            runBuildSh.toFile().setExecutable(true);
            Files.writeString(Paths.get(jbsDirectory.toString(), "Containerfile"), getContainerFile());
        } catch (IOException e) {
            Log.errorf("Unable to write Containerfile", e);
            throw new RuntimeException(e);
        }
    }


    private String getContainerFile() {
        String containerFile = """
            FROM %s
            USER 0
            WORKDIR /var/workdir
            ARG PROXY_URL=""
            ENV PROXY_URL=$PROXY_URL
            ENV http_proxy=http://localhost:8080
            ENV https_proxy=${http_proxy}
            ENV HTTP_PROXY=${http_proxy}
            ENV HTTPS_PROXY=${http_proxy}
            COPY .jbs/run-build.sh /var/workdir
            COPY . /var/workdir/workspace/source/
            RUN /var/workdir/run-build.sh
            """.formatted(recipeImage);

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
                    COPY --from=1 /var/workdir/workspace/artifacts /deployment/
                    """.formatted(buildRequestProcessorImage);
        } else {
            containerFile +=
                """
                    FROM scratch
                    COPY --from=0 /var/workdir/workspace/artifacts /deployment/
                    """;
        }

        Log.warnf("### containerFile is\n%s", containerFile);

        return containerFile;
    }

    /**
     * This will generate the settings and toolchain into the standard $HOME/.m2 location and configure
     * altDeploymentDirectory to be used by default.
     */
    private String getMavenSetup() {

        return """
            echo "MAVEN_HOME:$MAVEN_HOME"
            PATH="${MAVEN_HOME}/bin:$PATH"

            if [ ! -d "${MAVEN_HOME}" ]; then
                echo "Maven home directory not found at ${MAVEN_HOME}" >&2
                exit 1
            fi

            if [ -n "${PROXY_URL}" ]; then
            cat >${HOME}/.m2/settings.xml <<EOF
        <settings>
          <mirrors>
            <mirror>
              <id>indy-mvn</id>
              <url>${PROXY_URL}</url>
              <mirrorOf>*</mirrorOf>
            </mirror>
          </mirrors>
        EOF
            else
                cat >${HOME}/.m2/settings.xml <<EOF
        <settings>
        EOF
            fi
            cat >>${HOME}/.m2/settings.xml <<EOF
          <!-- Allows a secondary Maven build to use results of prior (e.g. Gradle) deployment -->
          <profiles>
            <profile>
              <id>secondary</id>
              <repositories>
                <repository>
                  <id>artifacts</id>
                  <url>file://${JBS_WORKDIR}/artifacts</url>
                  <releases>
                    <enabled>true</enabled>
                    <checksumPolicy>ignore</checksumPolicy>
                  </releases>
                </repository>
              </repositories>
              <pluginRepositories>
                <pluginRepository>
                  <id>artifacts</id>
                  <url>file://${JBS_WORKDIR}/artifacts</url>
                  <releases>
                    <enabled>true</enabled>
                    <checksumPolicy>ignore</checksumPolicy>
                  </releases>
                </pluginRepository>
              </pluginRepositories>
            </profile>
            <profile>
              <id>local-deployment</id>
              <properties>
                <altDeploymentRepository>
                  local::file://${JBS_WORKDIR}/artifacts
                </altDeploymentRepository>
              </properties>
            </profile>
          </profiles>

          <activeProfiles>
            <activeProfile>secondary</activeProfile>
            <activeProfile>local-deployment</activeProfile>
          </activeProfiles>

          <interactiveMode>false</interactiveMode>

          <proxies>
            <proxy>
              <id>domain-proxy</id>
              <active>true</active>
              <protocol>http</protocol>
              <host>localhost</host>
              <port>8080</port>
            </proxy>
          </proxies>

        </settings>
        EOF

            TOOLCHAINS_XML=${HOME}/.m2/toolchains.xml

            cat >"$TOOLCHAINS_XML" <<EOF
        <?xml version="1.0" encoding="UTF-8"?>
        <toolchains>
        EOF

            if [ "%s" = "7" ]; then
                JAVA_VERSIONS="7:1.7.0 8:1.8.0 11:11"
            else
                JAVA_VERSIONS="8:1.8.0 9:11 11:11 17:17 21:21 22:23 23:23"
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


    private String getGradleSetup() {
        return """
                echo "GRADLE_HOME:$GRADLE_HOME"
                PATH="${GRADLE_HOME}/bin:$PATH"

                if [ ! -d "${GRADLE_HOME}" ]; then
                    echo "Gradle home directory not found at ${GRADLE_HOME}" >&2
                    exit 1
                fi

                cat > "${GRADLE_USER_HOME}"/gradle.properties << EOF
            org.gradle.console=plain

            # Increase timeouts
            systemProp.org.gradle.internal.http.connectionTimeout=600000
            systemProp.org.gradle.internal.http.socketTimeout=600000
            systemProp.http.socketTimeout=600000
            systemProp.http.connectionTimeout=600000

            # Settings for <https://github.com/vanniktech/gradle-maven-publish-plugin>
            RELEASE_REPOSITORY_URL=file://${JBS_WORKDIR}/artifacts
            RELEASE_SIGNING_ENABLED=false
            mavenCentralUsername=
            mavenCentralPassword=

            # Default values for common enforced properties
            sonatypeUsername=jbs
            sonatypePassword=jbs

            # Default deployment target
            # https://docs.gradle.org/current/userguide/build_environment.html#sec:gradle_system_properties
            systemProp.maven.repo.local=${JBS_WORKDIR}/artifacts
            EOF
            """;
    }


    private String getAntSetup() {
        return """
                echo "ANT_HOME:$ANT_HOME"
                PATH="${ANT_HOME}/bin:$PATH"

                if [ ! -d "${ANT_HOME}" ]; then
                    echo "Ant home directory not found at ${ANT_HOME}" >&2
                    exit 1
                fi

                if [ -n "${PROXY_URL}" ]; then
                    cat > ivysettings.xml << EOF
            <ivysettings>
                <property name="cache-url" value="${PROXY_URL}"/>
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
            """;
    }

    private String getSbtSetup() {
        return """
        echo "SBT_HOME:$SBT_HOME"
        PATH="${SBT_HOME}/bin:$PATH"

        if [ ! -d "${SBT_HOME}" ]; then
        echo "SBT home directory not found at ${SBT_HOME}" >&2
        exit 1
        fi

        if [ -n "${PROXY_URL}" ]; then
        cat > "${HOME}/.sbt/repositories" <<EOF
            [repositories]
        local
        my-maven-proxy-releases: ${PROXY_URL}
        EOF
            fi
                # TODO: we may need .allowInsecureProtocols here for minikube based tests that don't have access to SSL
        cat >"$HOME/.sbt/1.0/global.sbt" <<EOF
        publishTo := Some(("MavenRepo" at s"file:${JBS_WORKDIR}/artifacts")),
        EOF
        """;
    }
}

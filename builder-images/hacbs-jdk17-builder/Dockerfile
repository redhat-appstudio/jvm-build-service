FROM registry.access.redhat.com/ubi8/openjdk-17
USER root
ENV \
    LANG="en_US.UTF-8" \
    LANGUAGE="en_US:en" \
    LC_ALL="en_US.UTF-8" \
    APP_HOME="/home/hacbs" \
    MAVEN_HOME="/usr/share/maven" \
    MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1" \
    MAVEN_VERSION="3.8.6" \
    GRADLE_HOME="/usr/share/gradle" \
    GRADLE_OPTS="-Dorg.gradle.daemon=false" \
    GRADLE_VERSION="7.5" \
    GRADLE_MANIPULATOR_HOME="/usr/share/gradle-manipulator" \
    GRADLE_MANIPULATOR_VERSION="3.7" \
    HOME="/home/hacbs" \
    NSS_WRAPPER_PASSWD="/etc/passwd" \
    GRADLE_DOWNLOAD_SHA256=cb87f222c5585bd46838ad4db78463a5c5f3d336e5e2b98dc7c0c586527351c2 \
    CLI_JAR_DOWNLOAD_SHA256=8af7e87638bb237362bf8f486b489c251e474be1cdc40037ba79887c2f0803b9 \
    ANALYZER_INIT_DOWNLOAD_SHA256=3172f34e126d652efccce50ca98c58f9baca27d89b5482268d0a4cb44023aa7e \
    PATH="$PATH:$JAVA_HOME/bin" \
    BASE_URL=https://apache.osuosl.org/maven/maven-3/3.8.6/binaries \
    MAVEN_SHA=f790857f3b1f90ae8d16281f902c689e4f136ebe584aba45e4b1fa66c80cba826d3e0e52fdd04ed44b4c66f6d3fe3584a057c26dfcac544a60b301e6d0f91c26

RUN groupadd -r hacbs -g 1001 && useradd -u 1001 -r -g 1001 -m -d ${APP_HOME} -s /sbin/nologin -c "HACBS user" hacbs && cp /etc/passwd /home/hacbs/passwd && chown 1001:1001 /home/hacbs/passwd && mkdir /project && chown 1001:1001 /project

RUN microdnf --setopt=install_weak_deps=0 --setopt=tsflags=nodocs install -y apr-devel wget tar gzip gcc glibc-devel zlib-devel shadow-utils unzip gcc-c++ glibc-langpack-en buildah findutils podman bzip2-devel gcc-c++ libcurl-devel openssl-devel bc which lsof unzip git libstdc++-static glibc-static make autoconf automake libtool tar glibc-devel libgcc.i686 glibc-devel.i686 cmake diffutils file \
    && microdnf clean all \
    && rpm -q apr-devel wget tar gzip gcc glibc-devel zlib-devel shadow-utils unzip gcc-c++ glibc-langpack-en buildah findutils podman bzip2-devel gcc-c++ libcurl-devel openssl-devel bc which lsof unzip git libstdc++-static glibc-static make autoconf automake libtool tar glibc-devel libgcc.i686 glibc-devel.i686 cmake diffutils file

RUN rm /usr/bin/mvn && mkdir -p /usr/share/maven/ref && curl -fsSL -o /tmp/apache-maven.tar.gz ${BASE_URL}/apache-maven-3.8.6-bin.tar.gz   && echo "f790857f3b1f90ae8d16281f902c689e4f136ebe584aba45e4b1fa66c80cba826d3e0e52fdd04ed44b4c66f6d3fe3584a057c26dfcac544a60b301e6d0f91c26  /tmp/apache-maven.tar.gz" | sha512sum -c -   && tar -xzf /tmp/apache-maven.tar.gz -C /usr/share/maven --strip-components=1   && rm -f /tmp/apache-maven.tar.gz   && ln -s /usr/share/maven/bin/mvn /usr/bin/mvn

RUN set -o errexit -o nounset \
    && echo "Downloading Gradle Manipulator" \
    && wget --no-verbose --output-document=cli.jar "https://repo1.maven.org/maven2/org/jboss/gm/cli/3.7/cli-3.7.jar" \
    && wget --no-verbose --output-document=analyzer-init.gradle "https://repo1.maven.org/maven2/org/jboss/gm/analyzer/3.7/analyzer-3.7-init.gradle" \
    \
    && echo "Checking cli.jar download hash" \
    && echo "${CLI_JAR_DOWNLOAD_SHA256} *cli.jar" | sha256sum --check - \
    \
    && echo "Checking analyzer-init.gradle download hash" \
    && echo "${ANALYZER_INIT_DOWNLOAD_SHA256} *analyzer-init.gradle" | sha256sum --check - \
    \
    && echo "Installing Gradle Manipulator" \
    && mkdir "${GRADLE_MANIPULATOR_HOME}" \
    && mv cli.jar analyzer-init.gradle "${GRADLE_MANIPULATOR_HOME}/" \
    && mkdir "${GRADLE_MANIPULATOR_HOME}/bin" \
    && echo -e "#!/bin/sh\n\ntest -n \"\${GRADLE_HOME}\" || GRADLE_HOME=\"${GRADLE_HOME}\"\nARGS=\"-l \${GRADLE_HOME}\"\njava -jar ${GRADLE_MANIPULATOR_HOME}/cli.jar \"\${ARGS}\" --init-script=${GRADLE_MANIPULATOR_HOME}/analyzer-init.gradle \$@" > ${GRADLE_MANIPULATOR_HOME}/bin/gradle-manipulator \
    && chmod 755 ${GRADLE_MANIPULATOR_HOME}/bin/gradle-manipulator \
    && ${GRADLE_MANIPULATOR_HOME}/bin/gradle-manipulator --version \
    && ln --symbolic "${GRADLE_MANIPULATOR_HOME}/bin/gradle-manipulator" /usr/bin/gradle-manipulator \
    && /usr/bin/gradle-manipulator --version \
    && gradle-manipulator --version

RUN set -o errexit -o nounset \
    && echo "Downloading Gradle" \
    && wget --no-verbose --output-document=gradle.zip "https://services.gradle.org/distributions/gradle-7.5-bin.zip" \
    \
    && echo "Checking download hash" \
    && echo "${GRADLE_DOWNLOAD_SHA256} *gradle.zip" | sha256sum --check - \
    \
    && echo "Installing Gradle" \
    && unzip gradle.zip \
    && rm gradle.zip \
    && mv "gradle-7.5" "${GRADLE_HOME}/" \
    && ln --symbolic "${GRADLE_HOME}/bin/gradle" /usr/bin/gradle

LABEL \
    io.cekit.version="4.1.0" \
    io.k8s.description="HACBS builder image for building Java components." \
    io.k8s.display-name="HACBS BuilderImage" \
    io.openshift.tags="builder,java,hacbs" \
    maintainer="Stuart Douglas <sdouglas@redhat.com>"

# Clear package manager metadata
RUN microdnf clean all && [ ! -d /var/cache/yum ] || rm -rf /var/cache/yum
# Define the user
USER hacbs
# Define the working directory
WORKDIR /project

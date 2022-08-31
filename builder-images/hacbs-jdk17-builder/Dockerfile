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
    GRADLE_OPTS="-Dorg.gradle.daemon=false" \
    GRADLE_MANIPULATOR_HOME="/usr/share/gradle-manipulator" \
    GRADLE_MANIPULATOR_VERSION="3.7" \
    HOME="/home/hacbs" \
    NSS_WRAPPER_PASSWD="/etc/passwd" \
    GRADLE_DOWNLOAD_SHA256= \
    CLI_JAR_DOWNLOAD_SHA256=8af7e87638bb237362bf8f486b489c251e474be1cdc40037ba79887c2f0803b9 \
    ANALYZER_INIT_DOWNLOAD_SHA256=3172f34e126d652efccce50ca98c58f9baca27d89b5482268d0a4cb44023aa7e \
    PATH="$PATH:$JAVA_HOME/bin" \
    BASE_URL=https://apache.osuosl.org/maven/maven-3/3.8.6/binaries \
    MAVEN_SHA=f790857f3b1f90ae8d16281f902c689e4f136ebe584aba45e4b1fa66c80cba826d3e0e52fdd04ed44b4c66f6d3fe3584a057c26dfcac544a60b301e6d0f91c26

RUN groupadd -r hacbs -g 1001 && useradd -u 1001 -r -g 1001 -m -d ${APP_HOME} -s /sbin/nologin -c "HACBS user" hacbs && cp /etc/passwd /home/hacbs/passwd && chown 1001:1001 /home/hacbs/passwd && mkdir /project && chown 1001:1001 /project

RUN microdnf --setopt=install_weak_deps=0 --setopt=tsflags=nodocs install -y apr-devel autoconf automake bc buildah bzip2-devel cmake diffutils file findutils gcc gcc-c++ git glibc-devel glibc-devel.i686 glibc-langpack-en glibc-static golang gzip libcurl-devel libgcc.i686 libstdc++-static libtool lsof make openssl-devel podman shadow-utils tar unzip wget which zlib-devel \
    && microdnf clean all \
    && rpm -q apr-devel autoconf automake bc buildah bzip2-devel cmake diffutils file findutils gcc gcc-c++ git glibc-devel glibc-devel.i686 glibc-langpack-en glibc-static golang gzip libcurl-devel libgcc.i686 libstdc++-static libtool lsof make openssl-devel podman shadow-utils tar unzip wget which zlib-devel

RUN microdnf --setopt=install_weak_deps=0 --setopt=tsflags=nodocs install -y emacs-filesystem vim-filesystem
# XXX: RHEL 8 does not have ninja-build, so grabbing from CentOS 8
RUN rpm --import https://www.centos.org/keys/RPM-GPG-KEY-CentOS-Official \
    && rpm -q gpg-pubkey-8483c65d-5ccc5b19
RUN wget --no-verbose https://vault.centos.org/centos/8/PowerTools/x86_64/os/Packages/ninja-build-1.8.2-1.el8.x86_64.rpm \
    && rpm -K ninja-build-1.8.2-1.el8.x86_64.rpm \
    && rpm -ivh ninja-build-1.8.2-1.el8.x86_64.rpm \
    && rm ninja-build-1.8.2-1.el8.x86_64.rpm \
    && rpm -q ninja-build

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
    && echo -e "#!/bin/sh\n\nset -eu\nARGS=\"-l \${GRADLE_HOME}\"\njava -jar ${GRADLE_MANIPULATOR_HOME}/cli.jar \"\${ARGS}\" --init-script=${GRADLE_MANIPULATOR_HOME}/analyzer-init.gradle \$@" > ${GRADLE_MANIPULATOR_HOME}/bin/gradle-manipulator \
    && chmod 755 ${GRADLE_MANIPULATOR_HOME}/bin/gradle-manipulator \
    && ln --symbolic "${GRADLE_MANIPULATOR_HOME}/bin/gradle-manipulator" /usr/bin/gradle-manipulator
    #we test out gradle manipulator as part of the gradle installation


RUN set -o errexit -o nounset \
    && mkdir /opt/gradle \
         && echo "Downloading Gradle 7.4.2" \
    && wget --no-verbose --output-document=gradle.zip "https://services.gradle.org/distributions/gradle-7.4.2-bin.zip" \
    \
    && echo "Checking download hash 7.4.2" \
    && echo "29e49b10984e585d8118b7d0bc452f944e386458df27371b49b4ac1dec4b7fda *gradle.zip" | sha256sum --check - \
    \
    && echo "Installing Gradle 7.4.2" \
    && unzip gradle.zip \
    && rm gradle.zip \
    && mv "gradle-7.4.2" "/opt/gradle/7.4.2/" \
    && export GRADLE_HOME=/opt/gradle/7.4.2; gradle-manipulator --version true

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

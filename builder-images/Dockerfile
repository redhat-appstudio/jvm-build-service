FROM registry.access.redhat.com/ubi8/openjdk-1
USER root
# Install packages defined in the 'tooling-packages' module
RUN microdnf --setopt=install_weak_deps=0 --setopt=tsflags=nodocs install -y tar gzip gcc glibc-devel zlib-devel shadow-utils unzip gcc-c++ glibc-langpack-en buildah findutils podman bzip2-devel gcc-c++ libcurl-devel openssl-devel bc which lsof unzip git libstdc++-static glibc-static make autoconf automake libtool \
    && microdnf clean all \
    && rpm -q tar gzip gcc glibc-devel zlib-devel shadow-utils unzip gcc-c++ glibc-langpack-en buildah findutils podman bzip2-devel gcc-c++ libcurl-devel openssl-devel bc which lsof unzip git libstdc++-static glibc-static make autoconf automake libtool

ENV \
    LANG="en_US.UTF-8" \
    LANGUAGE="en_US:en" \
    LC_ALL="en_US.UTF-8"
###### /
###### END module 'encoding:1.0.0'

###### START module 'maven-binary:3.8.4'
###### \
        # Copy 'maven-binary' module general artifacts to '/tmp/artifacts/' destination
COPY \
    apache-maven-3.8.4-bin.tar.gz \
    /tmp/artifacts/
# Copy 'maven-binary' module content
COPY modules/maven-binary /tmp/scripts/maven-binary
# Set 'maven-binary' module defined environment variables
ENV \
     MAVEN_HOME="/usr/share/maven" \
    MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1" \
    MAVEN_VERSION="3.8.4"
# Custom scripts from 'maven-binary' module
USER root
RUN [ "sh", "-x", "/tmp/scripts/maven-binary/configure" ]
###### /
###### END module 'maven-binary:3.8.4'

###### START module 'gradle-binary:7.3'
###### \
        # Copy 'gradle-binary' module general artifacts to '/tmp/artifacts/' destination
        COPY \
            gradle.zip \
            /tmp/artifacts/
        # Copy 'gradle-binary' module content
        COPY modules/gradle-binary /tmp/scripts/gradle-binary
        # Set 'gradle-binary' module defined environment variables
        ENV \
            GRADLE_HOME="/usr/share/gradle" \
            GRADLE_OPTS="-Dorg.gradle.daemon=false" \
            GRADLE_VERSION="7.3"
        # Custom scripts from 'gradle-binary' module
        USER root
        RUN [ "sh", "-x", "/tmp/scripts/gradle-binary/configure" ]
###### /
###### END module 'gradle-binary:7.3'

###### START image 'quay.io/sdouglas/hacbs-jdk17-builder-test:test'
###### \
        # Switch to 'root' user to install 'quay.io/sdouglas/hacbs-jdk17-builder-test' image defined packages
        USER root
        # Install packages defined in the 'quay.io/sdouglas/hacbs-jdk17-builder-test' image
        RUN microdnf --setopt=install_weak_deps=0 --setopt=tsflags=nodocs install -y buildah findutils podman bzip2-devel gcc-c++ libcurl-devel openssl-devel bc which lsof unzip git libstdc++-static glibc-static make autoconf automake libtool \
            && microdnf clean all \
            && rpm -q buildah findutils podman bzip2-devel gcc-c++ libcurl-devel openssl-devel bc which lsof unzip git libstdc++-static glibc-static make autoconf automake libtool
        # Set 'quay.io/sdouglas/hacbs-jdk17-builder-test' image defined environment variables
        ENV \
            PATH="$PATH:$JAVA_HOME/bin"
        # Set 'quay.io/sdouglas/hacbs-jdk17-builder-test' image defined labels
        LABEL \
            io.cekit.version="4.1.0" \
            io.k8s.description="HACBS builder image for building Java components." \
            io.k8s.display-name="HACBS BuilderImage" \
            io.openshift.expose-services="8080:http" \
            io.openshift.tags="builder,java,hacbs" \
            maintainer="Stuart Douglas <sdouglas@redhat.com>"
        # Exposed ports in 'quay.io/sdouglas/hacbs-jdk17-builder-test' image
        EXPOSE 8080
###### /
###### END image 'quay.io/sdouglas/hacbs-jdk17-builder-test:test'



RUN [ ! -d /tmp/scripts ] || rm -rf /tmp/scripts
RUN [ ! -d /tmp/artifacts ] || rm -rf /tmp/artifacts
# Clear package manager metadata
RUN microdnf clean all && [ ! -d /var/cache/yum ] || rm -rf /var/cache/yum

# Define the user
# Define the working directory
WORKDIR /project
# Define run cmd
CMD ["mvn", "-v"]

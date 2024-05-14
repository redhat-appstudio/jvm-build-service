#!/usr/bin/env bash
export GRADLE_USER_HOME="$(workspaces.build-settings.path)/.gradle"
mkdir -p "${GRADLE_USER_HOME}"
mkdir -p "$HOME/.m2/"

#copy back the gradle folder for hermetic
cp -r /maven-artifacts/.gradle/* "$GRADLE_USER_HOME/" || true
cp -r /maven-artifacts/.m2/* "$HOME/.m2/" || true

cat > "${GRADLE_USER_HOME}"/gradle.properties << EOF
org.gradle.console=plain
# For Spring/Nebula Release Plugins
release.useLastTag=true
release.stage=final

# For https://github.com/Kotlin/kotlinx.team.infra
versionSuffix=

# Increase timeouts
systemProp.org.gradle.internal.http.connectionTimeout=600000
systemProp.org.gradle.internal.http.socketTimeout=600000
systemProp.http.socketTimeout=600000
systemProp.http.connectionTimeout=600000

# Settings for <https://github.com/vanniktech/gradle-maven-publish-plugin>
RELEASE_REPOSITORY_URL=file:$(workspaces.source.path)/artifacts
RELEASE_SIGNING_ENABLED=false
mavenCentralUsername=
mavenCentralPassword=

# Default values for common enforced properties
sonatypeUsername=jbs
sonatypePassword=jbs
EOF

if [ -d .hacbs-init ]; then
    rm -rf "${GRADLE_USER_HOME}"/init.d
    cp -r .hacbs-init "${GRADLE_USER_HOME}"/init.d
fi

#if we run out of memory we want the JVM to die with error code 134
export JAVA_OPTS="-XX:+CrashOnOutOfMemoryError"

export PATH="${JAVA_HOME}/bin:${PATH}"

#some gradle builds get the version from the tag
#the git init task does not fetch tags
#so just create one to fool the plugin
git config user.email "HACBS@redhat.com"
git config user.name "HACBS"
if [ -n "$(params.ENFORCE_VERSION)" ]; then
  echo "Creating tag $(params.PROJECT_VERSION) to match enforced version"
  git tag -m $(params.PROJECT_VERSION) -a $(params.PROJECT_VERSION) || true
else
  echo "Enforce version not set, recreating original tag $(params.TAG)"
  git tag -m $(params.TAG) -a $(params.TAG) || true
fi

if [ ! -d "${GRADLE_HOME}" ]; then
    echo "Gradle home directory not found at ${GRADLE_HOME}" >&2
    exit 1
fi

export LANG="en_US.UTF-8"
export LC_ALL="en_US.UTF-8"

#our dependency tracing breaks verification-metadata.xml
#TODO: should we disable tracing for these builds? It means we can't track dependencies directly, so we can't detect contaminants
rm -f gradle/verification-metadata.xml

echo "Running Gradle command with arguments: $@"
if [ ! -d $(workspaces.source.path)/source ]; then
  cp -r $(workspaces.source.path)/workspace $(workspaces.source.path)/source
fi
gradle -Dmaven.repo.local=$(workspaces.source.path)/artifacts --info --stacktrace "$@"  | tee $(workspaces.source.path)/logs/gradle.log

mkdir -p $(workspaces.source.path)/build-info
cp -r "${GRADLE_USER_HOME}" $(workspaces.source.path)/build-info/.gradle
cp -r "${HOME}/.m2" $(workspaces.source.path)/build-info/.m2

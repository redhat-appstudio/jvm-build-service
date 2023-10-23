#!/usr/bin/env bash
export GRADLE_USER_HOME="$(workspaces.build-settings.path)/.gradle"
mkdir -p "${GRADLE_USER_HOME}"
mkdir -p "$HOME/.m2/"

#copy back the gradle folder for hermetic
cp -r /maven-artifacts/.gradle/* "$GRADLE_USER_HOME/" || true
cp -r /maven-artifacts/.m2/* "$HOME/.m2/" || true

cat > "${GRADLE_USER_HOME}"/gradle.properties << EOF
org.gradle.caching=false
org.gradle.console=plain
# This prevents the daemon from running (which is unnecessary in one-off builds) and increases the memory allocation
org.gradle.daemon=false
# For Spring/Nebula Release Plugins
release.useLastTag=true

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
EOF
cat > "${GRADLE_USER_HOME}"/init.gradle << EOF
allprojects {
    buildscript {
        repositories {
            mavenLocal()
            maven {
                name "HACBS Maven Repository"
                url "$(params.CACHE_URL)"
                //allowInsecureProtocol = true
            }
        }
    }
    repositories {
        mavenLocal()
        maven {
            name "HACBS Maven Repository"
            url "$(params.CACHE_URL)"
            //allowInsecureProtocol = true
        }
    }
}

settingsEvaluated { settings ->
    settings.pluginManagement {
        repositories {
            mavenLocal()
            maven {
                name "HACBS Maven Repository"
                url "$(params.CACHE_URL)"
                //allowInsecureProtocol = true
            }
        }
    }
}
EOF

#if we run out of memory we want the JVM to die with error code 134
export JAVA_OPTS="-XX:+CrashOnOutOfMemoryError"

export PATH="${JAVA_HOME}/bin:${PATH}"

#some gradle builds get the version from the tag
#the git init task does not fetch tags
#so just create one to fool the plugin
git config user.email "HACBS@redhat.com"
git config user.name "HACBS"
if [ -z "$(params.ENFORCE_VERSION)" ]
then
  echo "Enforce version not set, recreating original tag $(params.TAG)"
  git tag -m $(params.TAG) -a $(params.TAG) || true
else
  echo "Creating tag $(params.ENFORCE_VERSION) to match enforced version"
  git tag -m $(params.ENFORCE_VERSION) -a $(params.ENFORCE_VERSION) || true
fi

if [ ! -d "${GRADLE_HOME}" ]; then
    echo "Gradle home directory not found at ${GRADLE_HOME}" >&2
    exit 1
fi

case "${TOOL_VERSION}" in
    [78].*)
        sed -i -e 's|//allowInsecureProtocol|allowInsecureProtocol|g' ${GRADLE_USER_HOME}/init.gradle
        ;;
esac

export LANG="en_US.UTF-8"
export LC_ALL="en_US.UTF-8"

#we want to pass in additional params to GME
#but not the actual goals
#TODO: add GME params and remove this
ADDITIONAL_ARGS=$(echo "$@" | sed -e 's/build\($\| \)//')
ADDITIONAL_ARGS=$(echo "$ADDITIONAL_ARGS" | sed -e 's/publish\($\| \)//')
ADDITIONAL_ARGS=$(echo "$ADDITIONAL_ARGS" | sed -e 's/uploadArchives\($\| \)//')

echo ADDITIONAL_ARGS="${ADDITIONAL_ARGS}"

INIT_SCRIPTS=""
for i in .hacbs-init/*
do
  INIT_SCRIPTS="$INIT_SCRIPTS -I $(pwd)/$i"
done
echo "INIT SCRIPTS: $INIT_SCRIPTS"

#our dependency tracing breaks verification-metadata.xml
#TODO: should we disable tracing for these builds? It means we can't track dependencies directly, so we can't detect contaminants
rm -f gradle/verification-metadata.xml

gradle-manipulator $INIT_SCRIPTS -DAProxDeployUrl=file:$(workspaces.source.path)/artifacts --no-colour --info --stacktrace -l "${GRADLE_HOME}" -DdependencySource=NONE -DignoreUnresolvableDependencies=true -DpluginRemoval=ALL -DversionModification=false "${ADDITIONAL_ARGS}" generateAlignmentMetadata  | tee $(workspaces.source.path)/logs/gme.log

echo "Running Gradle command with arguments: $@"
if [ ! -d $(workspaces.source.path)/source ]; then
  cp -r $(workspaces.source.path)/workspace $(workspaces.source.path)/source
fi
gradle $INIT_SCRIPTS -DAProxDeployUrl=file:$(workspaces.source.path)/artifacts --info --stacktrace -Prelease.version=$(params.ENFORCE_VERSION) "$@"  | tee $(workspaces.source.path)/logs/gradle.log

mkdir -p $(workspaces.source.path)/build-info
cp -r "${GRADLE_USER_HOME}" $(workspaces.source.path)/build-info/.gradle
cp -r "${HOME}/.m2" $(workspaces.source.path)/build-info/.m2

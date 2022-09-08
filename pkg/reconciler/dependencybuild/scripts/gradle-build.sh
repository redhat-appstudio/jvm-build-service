#!/usr/bin/env bash
set -eu

export GRADLE_USER_HOME="$(workspaces.build-settings.path)/.gradle"
echo "@=$@"

export PATH="${JAVA_HOME}/bin:${PATH}"

if [ -z "$(params.TOOL_VERSION)" ]; then
    echo "TOOL_VERSION has not been set" >&2
    exit 1
fi

TOOL_VERSION="$(params.TOOL_VERSION)"
export GRADLE_HOME="/opt/gradle/${TOOL_VERSION}"
echo "GRADLE_HOME=${GRADLE_HOME}"

if [ ! -d "${GRADLE_HOME}" ]; then
    echo "Gradle home directory not found at ${GRADLE_HOME}" >&2
    exit 1
fi

export PATH="${GRADLE_HOME}/bin:${PATH}"
case "${TOOL_VERSION}" in
    7.*)
        sed -i -e 's|//allowInsecureProtocol|allowInsecureProtocol|g' ${GRADLE_USER_HOME}/init.gradle
        ;;
esac

export LANG="en_US.UTF-8"
export LC_ALL="en_US.UTF-8"

# FIXME: additionalArgs is added to args, but we need additionalArgs only; assume that we know the original tasks so that we can remove them
ADDITIONAL_ARGS=$(echo "$@" | sed 's/build publish \?//')
echo ADDITIONAL_ARGS="${ADDITIONAL_ARGS}"

if [ -n "$(params.ENFORCE_VERSION)" ]; then
    gradle-manipulator --no-colour --info --stacktrace -l "${GRADLE_HOME}" $(params.GRADLE_MANIPULATOR_ARGS) -DversionOverride=$(params.ENFORCE_VERSION) "${ADDITIONAL_ARGS}" generateAlignmentMetadata || exit 1
else
    gradle-manipulator --no-colour --info --stacktrace -l "${GRADLE_HOME}" $(params.GRADLE_MANIPULATOR_ARGS) "${ADDITIONAL_ARGS}" generateAlignmentMetadata || exit 1
fi

gradle -DAProxDeployUrl=file:$(workspaces.source.path)/hacbs-jvm-deployment-repo --info --stacktrace "$@" || exit 1

# fix-permissions-for-builder
chown 1001:1001 -R $(workspaces.source.path)

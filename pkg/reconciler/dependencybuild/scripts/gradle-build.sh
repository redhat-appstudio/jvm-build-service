#!/usr/bin/env bash
set -o verbose
set -eu
set -o pipefail
if [ -n "$(params.CONTEXT_DIR)" ]
then
    cd $(params.CONTEXT_DIR)
fi

export GRADLE_USER_HOME="$(workspaces.build-settings.path)/.gradle"
echo "@=$@"

export PATH="${JAVA_HOME}/bin:${PATH}"

mkdir -p $(workspaces.source.path)/logs
mkdir -p $(workspaces.source.path)/packages
{{INSTALL_PACKAGE_SCRIPT}}

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
    [78].*)
        sed -i -e 's|//allowInsecureProtocol|allowInsecureProtocol|g' ${GRADLE_USER_HOME}/init.gradle
        ;;
esac

export LANG="en_US.UTF-8"
export LC_ALL="en_US.UTF-8"

# FIXME: additionalArgs is added to args, but we need additionalArgs only; assume that we know the original tasks so that we can remove them
ADDITIONAL_ARGS=$(echo "$@" | sed -e 's/build \(publish\|uploadArchives\)\($\| \)//')
echo ADDITIONAL_ARGS="${ADDITIONAL_ARGS}"

INIT_SCRIPTS=""
for i in .hacbs-init/*
do
  INIT_SCRIPTS="$INIT_SCRIPTS -I $(pwd)/$i"
done
echo "INIT SCRIPTS: $INIT_SCRIPTS"

#This is replaced when the task is created by the golang code
cat <<EOF
Pre build script: {{PRE_BUILD_SCRIPT}}
EOF

{{PRE_BUILD_SCRIPT}}

#our dependency tracing breaks verification-metadata.xml
#TODO: should we disable tracing for these builds? It means we can't track dependencies directly, so we can't detect contaminants
rm -f gradle/verification-metadata.xml

cp -r $(workspaces.source.path)/workspace $(workspaces.source.path)/source

gradle $INIT_SCRIPTS -DAProxDeployUrl=file:$(workspaces.source.path)/artifacts --info --stacktrace -Prelease.version=$(params.ENFORCE_VERSION) "$@"  | tee $(workspaces.source.path)/logs/gradle.log

# This is replaced when the task is created by the golang code
cat <<EOF
Post build script: {{POST_BUILD_SCRIPT}}
EOF
{{POST_BUILD_SCRIPT}}

#!/usr/bin/env bash
set -o verbose
set -eu
set -o pipefail
if [ -n "$(params.CONTEXT_DIR)" ]
then
    cd $(params.CONTEXT_DIR)
fi
mkdir -p $(workspaces.source.path)/logs
mkdir -p $(workspaces.source.path)/packages
{{INSTALL_PACKAGE_SCRIPT}}

#This is replaced when the task is created by the golang code
cat <<EOF
Pre build script: {{PRE_BUILD_SCRIPT}}
EOF
{{PRE_BUILD_SCRIPT}}

if [ -z "$(params.ENFORCE_VERSION)" ]
then
  echo "Enforce version not set, skipping"
else
  echo "Setting version to $(params.ENFORCE_VERSION)"
  mvn -B -e -s "$(workspaces.build-settings.path)/settings.xml" org.codehaus.mojo:versions-maven-plugin:2.12.0:set -DnewVersion="$(params.ENFORCE_VERSION)"  | tee $(workspaces.source.path)/logs/enforce-version.log
fi


#if we run out of memory we want the JVM to die with error code 134
export MAVEN_OPTS="-XX:+CrashOnOutOfMemoryError"

echo "Running Maven command with arguments: $@"

cp -r $(workspaces.source.path)/workspace $(workspaces.source.path)/source
#we can't use array parameters directly here
#we pass them in as goals

mvn -B -e -s "$(workspaces.build-settings.path)/settings.xml" "$@" "-DaltDeploymentRepository=local::file:$(workspaces.source.path)/artifacts" "org.apache.maven.plugins:maven-deploy-plugin:3.0.0-M2:deploy" | tee $(workspaces.source.path)/logs/maven-online.log


if [ "$(params.REQUIRES_INTERNET)" == "false" ]
then
    rm -r $(workspaces.source.path)/artifacts
    microdnf install iproute
    TASK="ip link set dev lo up && mvn -B -e -s $(workspaces.build-settings.path)/settings.xml "$@" -DaltDeploymentRepository=local::file:$(workspaces.source.path)/artifacts org.apache.maven.plugins:maven-deploy-plugin:3.0.0-M2:deploy | tee $(workspaces.source.path)/logs/maven.log"
    unshare -n -Ufp -r -- sh -c "$TASK"
fi

mkdir $(workspaces.source.path)/build-info
cp -r /root/.[^.]* $(workspaces.source.path)/build-info

# This is replaced when the task is created by the golang code
cat <<EOF
Post build script: {{POST_BUILD_SCRIPT}}
EOF
{{POST_BUILD_SCRIPT}}

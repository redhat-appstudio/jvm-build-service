#!/usr/bin/env bash

set -eu
set -o pipefail

if [ -n "$(params.CONTEXT_DIR)" ]
then
    cd $(params.CONTEXT_DIR)
fi

# Fix this when we no longer need to run as root
export HOME=/root

TOOL_VERSION="$(params.TOOL_VERSION)"
export ANT_HOME="/opt/ant/${TOOL_VERSION}"
echo "ANT_HOME=${ANT_HOME}"

if [ ! -d "${ANT_HOME}" ]; then
    echo "Ant home directory not found at ${ANT_HOME}" >&2
    exit 1
fi

export PATH="${ANT_HOME}/bin:${PATH}"

mkdir $(workspaces.source.path)/logs
mkdir $(workspaces.source.path)/packages
{{INSTALL_PACKAGE_SCRIPT}}

# This is replaced when the task is created by the golang code
cat <<EOF
Pre build script: {{PRE_BUILD_SCRIPT}}
EOF
{{PRE_BUILD_SCRIPT}}

cp -r $(workspaces.source.path)/workspace $(workspaces.source.path)/source

echo -n "Command: "
echo "$(which ant) $@"

# XXX: It's possible that build.xml is not in the root directory
if [ -r "ivysettings.xml" ]; then
    eval "ant $@" | tee $(workspaces.source.path)/logs/ant.log
else
    echo "Required file ivysettings.xml was not found" >&2
    exit 1
fi

tar czf "$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz" -C "$(workspaces.source.path)/hacbs-jvm-deployment-repo" .

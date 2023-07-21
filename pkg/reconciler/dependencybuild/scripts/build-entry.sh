#!/usr/bin/env bash
set -o verbose
set -eu
set -o pipefail

cp -r -a  /original-content/* $(workspaces.source.path)
cd $(workspaces.source.path)/workspace

if [ -n "$(params.CONTEXT_DIR)" ]
then
    cd $(params.CONTEXT_DIR)
fi

#fix this when we no longer need to run as root
export HOME=/root

mkdir -p $(workspaces.source.path)/logs $(workspaces.source.path)/packages $(workspaces.source.path)/build-info

{{INSTALL_PACKAGE_SCRIPT}}

#This is replaced when the task is created by the golang code
{{PRE_BUILD_SCRIPT}}

{{BUILD}}

{{POST_BUILD_SCRIPT}}

#!/usr/bin/env bash

cd $(workspaces.source.path)/source

if [ -n "$(params.CONTEXT_DIR)" ]
then
    cd $(params.CONTEXT_DIR)
fi

{{INSTALL_PACKAGE_SCRIPT}}

{{PRE_BUILD_SCRIPT}}

{{BUILD}}

{{POST_BUILD_SCRIPT}}

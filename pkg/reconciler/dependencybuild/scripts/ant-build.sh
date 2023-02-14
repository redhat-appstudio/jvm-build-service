#!/usr/bin/env bash
set -o verbose
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

# XXX: It's possible that build.xml is not in the root directory
cat > ivysettings.xml << EOF
<ivysettings>
    <property name="cache-url" value="$(params.CACHE_URL)"/>
    <property name="default-pattern" value="[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"/>
    <property name="local-pattern" value="\${user.home}/.m2/repository/[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"/>
    <settings defaultResolver="default"/>
    <resolvers>
        <ibiblio name="default" root="\${cache-url}" pattern="\${default-pattern}" m2compatible="true"/>
        <filesystem name="local" m2compatible="true">
            <artifact pattern="\${local-pattern}"/>
            <ivy pattern="\${local-pattern}"/>
        </filesystem>
        <chain name="default">
            <resolver ref="local"/>
            <resolver ref="default"/>
        </chain>
    </resolvers>
</ivysettings>
EOF

# This is replaced when the task is created by the golang code
cat <<EOF
Pre build script: {{PRE_BUILD_SCRIPT}}
EOF
{{PRE_BUILD_SCRIPT}}

cp -r $(workspaces.source.path)/workspace $(workspaces.source.path)/source

echo -n "Command: "
echo "$(which ant) $@"

eval "ant $@" | tee $(workspaces.source.path)/logs/ant.log


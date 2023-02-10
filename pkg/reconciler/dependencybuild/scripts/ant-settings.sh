#!/usr/bin/env bash

set -eu
set -o pipefail

# XXX: It's possible that build.xml is not in the root directory
cat > "$(workspaces.source.path)/ivysettings.xml" << EOF
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

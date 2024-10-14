#!/usr/bin/env bash

#if [ ! -d "${ANT_HOME}" ]; then
#    echo "Ant home directory not found at ${ANT_HOME}" >&2
#    exit 1
#fi

#if [ -z ${JBS_DISABLE_CACHE+x} ]; then
#    # XXX: It's possible that build.xml is not in the root directory
#    cat > ivysettings.xml << EOF
#<ivysettings>
#    <property name="cache-url" value="${CACHE_URL}"/>
#    <property name="default-pattern" value="[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"/>
#    <property name="local-pattern" value="\${user.home}/.m2/repository/[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]"/>
#    <settings defaultResolver="defaultChain"/>
#    <resolvers>
#        <ibiblio name="default" root="\${cache-url}" pattern="\${default-pattern}" m2compatible="true"/>
#        <filesystem name="local" m2compatible="true">
#            <artifact pattern="\${local-pattern}"/>
#            <ivy pattern="\${local-pattern}"/>
#        </filesystem>
#        <chain name="defaultChain">
#            <resolver ref="local"/>
#            <resolver ref="default"/>
#        </chain>
#    </resolvers>
#</ivysettings>
#EOF
#fi

echo "Running $(which ant) with arguments: $@"
eval "ant $@" | tee $(workspaces.source.path)/logs/ant.log

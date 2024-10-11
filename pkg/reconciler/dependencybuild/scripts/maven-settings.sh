#!/usr/bin/env bash

#if [ ! -z ${JBS_DISABLE_CACHE+x} ]; then
#    cat >"$(workspaces.build-settings.path)"/settings.xml <<EOF
#    <settings>
#EOF
#else
#    cat >"$(workspaces.build-settings.path)"/settings.xml <<EOF
#    <settings>
#      <mirrors>
#        <mirror>
#          <id>mirror.default</id>
#          <url>${CACHE_URL}</url>
#          <mirrorOf>*</mirrorOf>
#        </mirror>
#      </mirrors>
#EOF
#fi
#
#cat >>"$(workspaces.build-settings.path)"/settings.xml <<EOF
#  <!-- Off by default, but allows a secondary Maven build to use results of prior (e.g. Gradle) deployment -->
#  <profiles>
#    <profile>
#      <id>gradle</id>
#      <activation>
#        <property>
#          <name>useJBSDeployed</name>
#        </property>
#      </activation>
#      <repositories>
#        <repository>
#          <id>artifacts</id>
#          <url>file://$(workspaces.source.path)/artifacts</url>
#          <releases>
#            <enabled>true</enabled>
#            <checksumPolicy>ignore</checksumPolicy>
#          </releases>
#        </repository>
#      </repositories>
#      <pluginRepositories>
#        <pluginRepository>
#          <id>artifacts</id>
#          <url>file://$(workspaces.source.path)/artifacts</url>
#          <releases>
#            <enabled>true</enabled>
#            <checksumPolicy>ignore</checksumPolicy>
#          </releases>
#        </pluginRepository>
#      </pluginRepositories>
#    </profile>
#  </profiles>
#</settings>
#EOF

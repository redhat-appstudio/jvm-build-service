#!/usr/bin/env bash

cat >"$(workspaces.build-settings.path)"/settings.xml <<EOF
<settings>
  <mirrors>
    <mirror>
      <id>mirror.default</id>
      <url>${CACHE_URL}</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>

  <!-- Off by default, but allows a secondary Maven build to use results of prior (e.g. Gradle) deployment -->
  <profiles>
    <profile>
      <id>gradle</id>
      <activation>
        <property>
          <name>useJBSDeployed</name>
        </property>
      </activation>
      <repositories>
        <repository>
          <id>artifacts</id>
          <url>file://$(workspaces.source.path)/artifacts</url>
          <releases>
            <enabled>true</enabled>
            <checksumPolicy>ignore</checksumPolicy>
          </releases>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>artifacts</id>
          <url>file://$(workspaces.source.path)/artifacts</url>
          <releases>
            <enabled>true</enabled>
            <checksumPolicy>ignore</checksumPolicy>
          </releases>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
</settings>
EOF

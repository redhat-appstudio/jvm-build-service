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
</settings>
EOF

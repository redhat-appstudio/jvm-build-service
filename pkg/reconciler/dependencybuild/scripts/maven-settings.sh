#!/usr/bin/env bash
set -eu

# fix-permissions-for-builder
chown 1001:1001 -R "$(workspaces.source.path)/source"

cat >"$(workspaces.build-settings.path)"/settings.xml <<EOF
<settings>
  <mirrors>
    <mirror>
      <id>mirror.default</id>
      <url>$(params.CACHE_URL)</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>
</settings>
EOF


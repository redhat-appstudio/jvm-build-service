#!/usr/bin/env bash
set -o verbose
set -eu
set -o pipefail

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


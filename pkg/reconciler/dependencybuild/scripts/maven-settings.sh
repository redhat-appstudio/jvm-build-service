#!/usr/bin/env bash
set -eu
set -o pipefail

mkdir "$(workspaces.source.path)/settings"
cat >"$(workspaces.source.path)/settings/settings.xml" <<EOF
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


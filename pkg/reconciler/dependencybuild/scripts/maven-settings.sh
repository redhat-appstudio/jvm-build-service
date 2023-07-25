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

TOOLCHAINS_XML="${HOME}/.m2/toolchains.xml"

cat >"$TOOLCHAINS_XML" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
EOF

if [ "$(params.JAVA_VERSION)" = "1.7" ]; then
    JAVA_VERSIONS="1.7:1.7.0 1.8:1.8.0 11:11"
else
    JAVA_VERSIONS="1.8:1.8.0 11:11 17:17"
fi

for i in $JAVA_VERSIONS; do
    version=$(echo $i | cut -d : -f 1)
    home=$(echo $i | cut -d : -f 2)
    cat >>"$TOOLCHAINS_XML" <<EOF
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>$version</version>
    </provides>
    <configuration>
      <jdkHome>/usr/lib/jvm/java-$home-openjdk</jdkHome>
    </configuration>
  </toolchain>
EOF
done

cat >>"$TOOLCHAINS_XML" <<EOF
</toolchains>
EOF


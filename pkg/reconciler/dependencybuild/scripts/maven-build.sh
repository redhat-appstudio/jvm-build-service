#!/usr/bin/env bash

mkdir -p "${HOME}/.m2/repository"
#copy back the gradle folder for hermetic
cp -r /maven-artifacts/* "$HOME/.m2/repository/" || true

if [ -z "$(params.TOOL_VERSION)" ]; then
    echo "TOOL_VERSION has not been set" >&2
    exit 1
fi

TOOL_VERSION="$(params.TOOL_VERSION)"
export MAVEN_HOME="/opt/maven/${TOOL_VERSION}"
echo "MAVEN_HOME=${MAVEN_HOME}"

if [ ! -d "${MAVEN_HOME}" ]; then
    echo "Maven home directory not found at ${MAVEN_HOME}" >&2
    exit 1
fi

PATH="$PATH:${MAVEN_HOME}/bin"

TOOLCHAINS_XML="$(workspaces.build-settings.path)"/toolchains.xml

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


if [ -z "$(params.ENFORCE_VERSION)" ]
then
  echo "Enforce version not set, skipping"
else
  echo "Setting version to $(params.ENFORCE_VERSION)"
  mvn -B -e -s "$(workspaces.build-settings.path)/settings.xml" -t "$(workspaces.build-settings.path)/toolchains.xml" org.codehaus.mojo:versions-maven-plugin:2.12.0:set -DnewVersion="$(params.ENFORCE_VERSION)"  | tee $(workspaces.source.path)/logs/enforce-version.log
fi


#if we run out of memory we want the JVM to die with error code 134
export MAVEN_OPTS="-XX:+CrashOnOutOfMemoryError"

echo "Running Maven command with arguments: $@"

if [ ! -d $(workspaces.source.path)/source ]; then
  cp -r $(workspaces.source.path)/workspace $(workspaces.source.path)/source
fi
#we can't use array parameters directly here
#we pass them in as goals
mvn -V -B -e -s "$(workspaces.build-settings.path)/settings.xml" -t "$(workspaces.build-settings.path)/toolchains.xml" "$@" "-DaltDeploymentRepository=local::file:$(workspaces.source.path)/artifacts" "org.apache.maven.plugins:maven-deploy-plugin:3.0.0-M2:deploy" | tee $(workspaces.source.path)/logs/maven.log

cp -r "${HOME}"/.m2/repository/* $(workspaces.source.path)/build-info

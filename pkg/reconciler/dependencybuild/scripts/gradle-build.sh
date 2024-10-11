#!/usr/bin/env bash

cat >> "${GRADLE_USER_HOME}"/gradle.properties << EOF
# For https://github.com/Kotlin/kotlinx.team.infra
versionSuffix=
EOF

if [ -d .hacbs-init ]; then
    rm -rf "${GRADLE_USER_HOME}"/init.d
    cp -r .hacbs-init "${GRADLE_USER_HOME}"/init.d
fi

#some gradle builds get the version from the tag
#the git init task does not fetch tags
#so just create one to fool the plugin
git config user.email "HACBS@redhat.com"
git config user.name "HACBS"
if [ ! -z ${ENFORCE_VERSION+x} ]; then
  echo "Creating tag ${PROJECT_VERSION} to match enforced version"
  git tag -m ${PROJECT_VERSION} -a ${PROJECT_VERSION} || true
fi

#our dependency tracing breaks verification-metadata.xml
#TODO: should we disable tracing for these builds? It means we can't track dependencies directly, so we can't detect contaminants
rm -f gradle/verification-metadata.xml

echo "Running Gradle command with arguments: $@"

gradle --info --stacktrace "$@" | tee $(workspaces.source.path)/logs/gradle.log
#gradle -Dmaven.repo.local=$(workspaces.source.path)/artifacts --info --stacktrace "$@" | tee $(workspaces.source.path)/logs/gradle.log

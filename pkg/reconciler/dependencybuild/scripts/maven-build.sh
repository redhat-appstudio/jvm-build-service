#!/usr/bin/env bash
set -eu
set -o pipefail

mkdir $(workspaces.source.path)/logs

if [ -z "$(params.ENFORCE_VERSION)" ]
then
  echo "Enforce version not set, skipping"
else
  echo "Setting version to $(params.ENFORCE_VERSION)"
  mvn -B -e -s "$(workspaces.build-settings.path)/settings.xml" org.codehaus.mojo:versions-maven-plugin:2.12.0:set -DnewVersion="$(params.ENFORCE_VERSION)"  | tee $(workspaces.source.path)/logs/enforce-version.log
fi

#This is replaced when the task is created by the golang code
cat <<EOF
Pre build script: {{PRE_BUILD_SCRIPT}}
EOF
{{PRE_BUILD_SCRIPT}}

#we can't use array parameters directly here
#we pass them in as goals
mvn -B -e -s "$(workspaces.build-settings.path)/settings.xml" $@ "-DaltDeploymentRepository=local::file:$(workspaces.source.path)/hacbs-jvm-deployment-repo" "org.apache.maven.plugins:maven-deploy-plugin:3.0.0-M2:deploy" | tee $(workspaces.source.path)/logs/maven.log


tar -czf "$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz" -C "$(workspaces.source.path)/hacbs-jvm-deployment-repo" .

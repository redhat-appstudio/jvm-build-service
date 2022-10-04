#!/usr/bin/env bash
set -eu
if [ -z "$(params.ENFORCE_VERSION)" ]
then
  echo "Enforce version not set, skipping"
else
  echo "Setting version to $(params.ENFORCE_VERSION)"
  mvn -B -e -s "$(workspaces.build-settings.path)/settings.xml" org.codehaus.mojo:versions-maven-plugin:2.12.0:set -DnewVersion="$(params.ENFORCE_VERSION)"
fi

#This is replaced when the task is created by the golang code
echo "Pre build script: {{PRE_BUILD_SCRIPT}}"
{{PRE_BUILD_SCRIPT}}

chown 1001:1001 -R $(workspaces.source.path)

#we can't use array parameters directly here
#we pass them in as goals
mvn -B -e -s "$(workspaces.build-settings.path)/settings.xml" $@ "-DaltDeploymentRepository=local::file:$(workspaces.source.path)/hacbs-jvm-deployment-repo" "org.apache.maven.plugins:maven-deploy-plugin:3.0.0-M2:deploy"


tar -czf "$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz" -C "$(workspaces.source.path)/hacbs-jvm-deployment-repo" .

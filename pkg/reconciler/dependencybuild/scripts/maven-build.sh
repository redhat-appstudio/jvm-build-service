#!/usr/bin/env bash
if [ -z "$(params.ENFORCE_VERSION)" ]
then
  echo "Enforce version not set, skipping"
else
  echo "Setting version to $(params.ENFORCE_VERSION)"
  mvn -B -e -s "$(workspaces.build-settings.path)/settings.xml" versions:set -DnewVersion="$(params.ENFORCE_VERSION)"
fi
curl --fail http://localhost:2000/deploy/result -o "$(results.contaminants.path)" || { cat "$(workspaces.build-settings.path)/sidecar.log" ; false ; }
cat "$(workspaces.build-settings.path)/sidecar.log"

#we can't use array parameters directly here
#we pass them in as goals
mvn -B -e -s "$(workspaces.build-settings.path)/settings.xml" $@ "-DaltDeploymentRepository=local::file:$(workspaces.source.path)/hacbs-jvm-deployment-repo" "org.apache.maven.plugins:maven-deploy-plugin:3.0.0-M2:deploy" || { cat $(workspaces.build-settings.path)/sidecar.log ; false ; }

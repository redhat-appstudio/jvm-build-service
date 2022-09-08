#!/bin/sh

tar -czf "$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz" -C "$(workspaces.source.path)/hacbs-jvm-deployment-repo" .
curl --data-binary @$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz http://localhost:2000/deploy

curl --fail http://localhost:2000/deploy/result -o "$(results.contaminants.path)" || { cat "$(workspaces.build-settings.path)/sidecar.log" ; false ; }
cat "$(workspaces.build-settings.path)/sidecar.log"

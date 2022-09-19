#!/bin/sh
set -eu

tar -czf "$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz" -C "$(workspaces.source.path)/hacbs-jvm-deployment-repo" .
curl --fail --data-binary @$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz http://jvm-build-workspace-artifact-cache.$(params.NAMESPACE).svc.cluster.local/v1/deploy/$(params.DEPENDENCY_BUILD)

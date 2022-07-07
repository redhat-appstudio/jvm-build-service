#!/bin/sh

# This command runs the sample-component-build pipeline to build
# the Quarkus getting-started quickstart: https://github.com/quarkusio/quarkus-quickstarts/tree/main/getting-started

DIR=`dirname "$0"`

echo
echo "👉 Running the pipeline with a sample project:"
echo

kubectl apply -f $DIR/openshift-specific-rbac.yaml || true

kubectl create -f $DIR/run-build-quarkus-security.yaml

echo
echo "🎉 Done! You can watch logs now with the following command: tkn pr logs --last -f"

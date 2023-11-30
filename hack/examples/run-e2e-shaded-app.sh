#!/bin/sh

# This command runs the sample-component-build pipeline to build
# https://github.com/stuartwdouglas/shaded-java-app - the "smaller" app picked to run in constrained openshift CI clusters

DIR=`dirname "$0"`

echo
echo "👉 Registering sample pipeline:"
echo

kustomize build $DIR | envsubst '${QUAY_USERNAME}' | kubectl apply -f -

echo
echo "👉 Running the pipeline with the smaller repo suited for e2e's on openshift CI:"
echo

kubectl create -f $DIR/run-e2e-shaded-app.yaml

echo
echo "🎉 Done! You can watch logs now with the following command: tkn pr logs --last -f"

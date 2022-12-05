#!/bin/sh

kubectl delete deployments.apps hacbs-jvm-operator -n jvm-build-service
# we don't restart the cache and local storage by default
# for most cases in development this is not necessary, and just slows things
# down by needing things to be re-cached/rebuilt

function cleanAllArtifacts() {
     kubectl delete namespaces test-jvm-namespace
}

kubectl delete deployments.apps jvm-build-workspace-artifact-cache
if [ "$1" = "--clean" ]; then
    cleanAllArtifacts
fi

DIR=`dirname $0`
kubectl apply -f $DIR/namespace.yaml
kubectl config set-context --current --namespace=test-jvm-namespace
kubectl delete secret jvm-build-image-secrets
kubectl create secret generic jvm-build-image-secrets --from-file=.dockerconfigjson=$HOME/.docker/config.json --type=kubernetes.io/dockerconfigjson
kubectl create secret generic jvm-build-git-secrets --from-literal .git-credentials="
https://$GITHUB_E2E_ORGANIZATION:$GITHUB_TOKEN@github.com
https://test:test@gitlab.com
"

JVM_BUILD_SERVICE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-controller \
JVM_BUILD_SERVICE_CACHE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-cache \
JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-build-request-processor:dev \
$DIR/patch-yaml.sh
kubectl apply -k $DIR/overlays/development


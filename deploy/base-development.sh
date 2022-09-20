#!/bin/sh

kubectl delete deployments.apps hacbs-jvm-operator -n jvm-build-service
# we don't restart the cache and local storage by default
# for most cases in development this is not necessary, and just slows things
# down by needing things to be re-cached/rebuilt

kubectl delete deployments.apps jvm-build-workspace-artifact-cache
#kubectl delete deployments.apps localstack -n jvm-build-service
#kubectl delete persistentvolumeclaims jvm-build-workspace-artifact-cache

DIR=`dirname $0`
kubectl apply -f $DIR/namespace.yaml
kubectl config set-context --current --namespace=test-jvm-namespace
JVM_BUILD_SERVICE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-controller \
JVM_BUILD_SERVICE_CACHE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-cache \
JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-build-request-processor:dev \
$DIR/patch-yaml.sh
kubectl apply -k $DIR/overlays/development

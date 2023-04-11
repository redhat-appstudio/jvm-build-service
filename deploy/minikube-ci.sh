#!/bin/sh

DIR=`dirname $0`
kubectl apply -f https://github.com/tektoncd/pipeline/releases/download/v0.41.1/release.yaml
while ! oc get pods -n tekton-pipelines | grep tekton-pipelines-controller | grep Running; do
    sleep 1
done

#CRDS are sometimes racey
kubectl apply -k $DIR/crds/base
sleep 2

kubectl delete --ignore-not-found deployments.apps hacbs-jvm-operator -n jvm-build-service
kubectl delete --ignore-not-found deployments.apps jvm-build-workspace-artifact-cache

DIR=`dirname $0`
kubectl apply -f $DIR/namespace.yaml
kubectl config set-context --current --namespace=test-jvm-namespace

JVM_BUILD_SERVICE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-controller \
JVM_BUILD_SERVICE_CACHE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-cache \
JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-build-request-processor:dev \
$DIR/patch-yaml.sh

#huge hack to deal with minikube local images, make sure they are never pulled
find $DIR -path \*development\*.yaml -exec sed -i s/Always/Never/ {} \;

kubectl apply -k $DIR/overlays/development

#this tells JBS we are in test mode and won't have a secure registry
kubectl annotate --overwrite jbsconfigs.jvmbuildservice.io --all jvmbuildservice.io/test-registry=true


# base-development.sh switches to the test-jvm-namespace namespace
kubectl create sa pipeline
kubectl apply -f $DIR/minikube-rbac.yaml

kubectl apply -f https://raw.githubusercontent.com/openshift/api/master/quota/v1/0000_03_quota-openshift_01_clusterresourcequota.crd.yaml
kubectl delete namespace test-jvm-namespace

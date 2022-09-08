#!/bin/sh

DIR=`dirname $0`
kubectl apply -f https://github.com/tektoncd/pipeline/releases/download/v0.34.1/release.yaml
while ! oc get pods -n tekton-pipelines | grep tekton-pipelines-controller | grep Running; do
    sleep 1
done

$DIR/base-development.sh

#minikube cannot access registry.redhat.io by default
#you need to have these credentials in your docker config
kubectl create secret docker-registry minikube-pull-secret --from-file=.dockerconfigjson=$HOME/.docker/config.json
kubectl patch serviceaccount pipeline -p '{"imagePullSecrets": [{"name": "minikube-pull-secret"}]}'
kubectl patch serviceaccount default -p '{"imagePullSecrets": [{"name": "minikube-pull-secret"}]}'
kubectl apply -f https://raw.githubusercontent.com/openshift/api/master/quota/v1/0000_03_quota-openshift_01_clusterresourcequota.crd.yaml

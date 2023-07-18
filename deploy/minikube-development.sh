#!/bin/sh

DIR=`dirname $0`
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/previous/v0.47.3/release.yaml

timeout=600 #10 minutes in seconds
endTime=$(( $(date +%s) + timeout ))

while ! oc get pods -n tekton-pipelines | grep tekton-pipelines-controller | grep Running; do
    sleep 1
    if [ $(date +%s) -gt $endTime ]; then
        exit 1
    fi
done
#we need to make sure the tekton webhook has its rules installed
while ! kubectl get mutatingwebhookconfigurations.admissionregistration.k8s.io webhook.pipeline.tekton.dev -o yaml | grep rules; do
    sleep 1
    if [ $(date +%s) -gt $endTime ]; then
            exit 1
    fi
done
echo "Tekton controller is running"




$DIR/base-development.sh  $1

# base-development.sh switches to the test-jvm-namespace namespace
kubectl create sa pipeline
kubectl apply -f $DIR/minikube-rbac.yaml

#minikube cannot access registry.redhat.io by default
#you need to have these credentials in your docker config
kubectl create secret docker-registry minikube-pull-secret --from-file=.dockerconfigjson=$HOME/.docker/config.json
kubectl patch serviceaccount pipeline -p '{"imagePullSecrets": [{"name": "minikube-pull-secret"}]}'
kubectl patch serviceaccount default -p '{"imagePullSecrets": [{"name": "minikube-pull-secret"}]}'
kubectl apply -f https://raw.githubusercontent.com/openshift/api/master/quota/v1/0000_03_quota-openshift_01_clusterresourcequota.crd.yaml

#!/bin/bash

DIR=`dirname $0`
oc apply -f ${DIR}/openshift-pipelines-subscription.yaml

while ! oc get pods -n openshift-pipelines | grep tekton-pipelines-controller | grep Running; do
    sleep 1
done
#we need to make sure the tekton webhook has its rules installed
while ! kubectl get mutatingwebhookconfigurations.admissionregistration.k8s.io webhook.pipeline.tekton.dev -o yaml | grep rules; do
    sleep 1
done
echo "Tekton controller is running"

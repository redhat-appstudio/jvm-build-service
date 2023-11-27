#!/bin/bash

DIR=`dirname $0`
oc apply -f ${DIR}/base/pipelines/openshift-pipelines-subscription.yaml
timeout=600 #10 minutes in seconds
endTime=$(( $(date +%s) + timeout ))

while ! oc get pods -n openshift-pipelines | grep tekton-pipelines-controller | grep Running; do
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

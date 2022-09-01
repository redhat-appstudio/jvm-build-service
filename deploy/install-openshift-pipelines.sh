#!/bin/bash

DIR=`dirname $0`
oc apply -f ${DIR}/openshift-pipelines-subscription.yaml

while ! oc get pods -n openshift-pipelines | grep tekton-pipelines-controller | grep Running; do
    sleep 1
done

echo "Tekton controller is running"

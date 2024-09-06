#!/bin/bash

DIR=`dirname $0`

# DEV_IP is only set in .github/workflows/minikube.yaml and only used in CI
if [ -n "$DEV_IP" ]; then
    echo "Altering templates to never pull images"
    #huge hack to deal with minikube local images, make sure they are never pulled
    find $DIR -path \*dev-template\*.yaml -exec sed -i s/Always/Never/ {} \;
fi

$DIR/minikube-development.sh --clean

# Deleting the jvm-build-config as its created with different settings in util.go::setupMinikube
kubectl delete --ignore-not-found=true jbsconfigs.jvmbuildservice.io jvm-build-config

# Revert hack above to avoid edits in place
if [ -n "$DEV_IP" ]; then
    find $DIR -path \*dev-template\*.yaml -exec sed -i s/Never/Always/ {} \;
fi

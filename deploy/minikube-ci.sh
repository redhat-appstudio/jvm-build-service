#!/bin/bash

DIR=`dirname $0`

$DIR/minikube-development.sh --clean

# Deleting the jvm-build-config as its created with different settings in util.go::setupMinikube
kubectl delete --ignore-not-found=true jbsconfigs.jvmbuildservice.io jvm-build-config

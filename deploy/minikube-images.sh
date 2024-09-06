#!/bin/bash

DIR=`dirname $0`/..
eval $(minikube -p minikube docker-env)

docker build -t quay.io/minikube/hacbs-jvm-cache:dev -f "$DIR/java-components/cache/src/main/docker/Dockerfile.all-in-one" "$DIR/java-components"
docker build -t quay.io/minikube/hacbs-jvm-build-request-processor:dev -f "$DIR/java-components/build-request-processor/src/main/docker/Dockerfile.all-in-one" "$DIR/java-components"
docker build -t quay.io/minikube/hacbs-jvm-cache:dev  "$DIR"

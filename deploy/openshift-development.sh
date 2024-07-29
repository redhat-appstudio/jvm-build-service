#!/bin/sh

deploy_maven_repo=false

if [ -z "$MAVEN_USERNAME" ]; then
    export MAVEN_USERNAME=admin
    deploy_maven_repo=true
fi
if [ -z "$MAVEN_PASSWORD" ]; then
    export MAVEN_PASSWORD=secret
    deploy_maven_repo=true
fi
if [ -z "$MAVEN_REPOSITORY" ]; then
    export MAVEN_REPOSITORY='http://jvm-build-maven-repo.$(context.taskRun.namespace).svc.cluster.local/releases'
    deploy_maven_repo=true
fi

DIR=`dirname $0`
$DIR/base-development.sh $1

if [ "$deploy_maven_repo" = true ]; then
    export REPOSILITE_IMAGE=$(sed -n 's/^FROM //p' $DIR/../openshift-with-appstudio-test/e2e/Dockerfile.reposilite)
    kubectl delete --ignore-not-found deployment,service,route jvm-build-maven-repo maven-repo -n test-jvm-namespace
    cat $DIR/maven-repo.yaml | envsubst '${MAVEN_PASSWORD} ${MAVEN_USERNAME} ${REPOSILITE_IMAGE}' | kubectl create -f -
fi

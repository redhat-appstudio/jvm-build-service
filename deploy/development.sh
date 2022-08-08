#!/bin/sh

kubectl delete deployments.apps hacbs-jvm-operator -n jvm-build-service
# we don't restart the cache and local storage by default
# for most cases in development this is not necessary, and just slows things
# down by needing things to be re-cached/rebuilt
#kubectl delete deployments.apps hacbs-jvm-cache -n jvm-build-service
#kubectl delete deployments.apps localstack -n jvm-build-service


DIR=`dirname $0`
kubectl apply -f $DIR/namespace.yaml
kubectl config set-context --current --namespace=test-jvm-namespace
find $DIR -name development -exec rm -r {} \;
find $DIR -name dev-template -exec cp -r {} {}/../development \;
find $DIR -path \*development\*.yaml -exec sed -i s/QUAY_USERNAME/${QUAY_USERNAME}/ {} \;
find $DIR -path \*development\*.yaml -exec sed -i s/dev-template/development/ {} \;

kubectl apply -k $DIR/overlays/development
#!/bin/sh

kubectl delete deployments.apps hacbs-jvm-operator -n jvm-build-service
kubectl delete deployments.apps hacbs-jvm-cache -n jvm-build-service
kubectl delete deployments.apps localstack -n jvm-build-service


DIR=`dirname $0`
kubectl apply -f $DIR/namespace.yaml
kubectl config set-context --current --namespace=jvm-build-service
find $DIR -name maven-v0.2.yaml -exec sed -i s/redhat-appstudio/${QUAY_USERNAME}/ {} \;
find $DIR -name maven-v0.2.yaml -exec sed -i s/3dcd8603061c7ab2cedfe128c80cac9726721a74/dev/ {} \;
find $DIR -name development -exec rm -r {} \;
find $DIR -name dev-template -exec cp -r {} {}/../development \;
find $DIR -path \*development\*.yaml -exec sed -i s/QUAY_USERNAME/${QUAY_USERNAME}/ {} \;
find $DIR -path \*development\*.yaml -exec sed -i s/dev-template/development/ {} \;

kubectl apply -k $DIR/overlays/development
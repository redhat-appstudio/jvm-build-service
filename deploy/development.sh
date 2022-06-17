#!/bin/sh

kubectl delete deployments.apps hacbs-jvm-operator

DIR=`dirname $0`
find $DIR -name development -exec rm -r {} \;
find $DIR -name dev-template -exec cp -r {} {}/../development \;
find $DIR -path \*development\*.yaml -exec sed -i s/QUAY_USERNAME/${QUAY_USERNAME}/ {} \;
find $DIR -path \*development\*.yaml -exec sed -i s/dev-template/development/ {} \;

kubectl apply -k $DIR/overlays/development
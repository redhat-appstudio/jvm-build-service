#!/bin/sh


echo "quay username:"
echo ${QUAY_USERNAME}

DIR=`dirname $0`
find $DIR -name ci-final -exec rm -r {} \;
find $DIR -name ci-template -exec cp -r {} {}/../ci-final \;
find $DIR -path \*ci-final\*.yaml -exec sed -i s/QUAY_USERNAME/${QUAY_USERNAME}/ {} \;
find $DIR -path \*ci-final\*.yaml -exec sed -i s/QUAY_TOKEN/${QUAY_TOKEN}/ {} \;

oc apply -f $DIR/overlays/ci-final/secret.yaml
oc apply -k $DIR/overlays/ci-final

#!/bin/sh

echo "Executing openshift-ci.sh"

DIR=`dirname $0`
echo "Running out of ${DIR}"
$DIR/install-openshift-pipelines.sh
oc apply -f $DIR/namespace.yaml
$DIR/patch-yaml.sh

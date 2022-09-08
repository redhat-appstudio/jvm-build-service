#!/bin/sh

echo "Executing openshift-ci.sh"

$DIR/patch-yaml.sh

DIR=`dirname $0`
$DIR/install-openshift-pipelines.sh
oc apply -f $DIR/namespace.yaml
$DIR/patch-yaml.sh

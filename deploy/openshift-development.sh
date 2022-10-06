#!/bin/sh

DIR=`dirname $0`
$DIR/install-openshift-pipelines.sh
$DIR/base-development.sh $1


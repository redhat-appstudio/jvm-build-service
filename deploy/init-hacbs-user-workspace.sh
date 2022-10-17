#!/bin/sh

DIR=`dirname $0`
#KCP_KUBECONFIG should match whatever you have set in your infra-deployments preview.env file
#QUAY_USERNAME and QUAY_TOKEN are the same env's you use in the other dev flow scripts
#QUAY_TAG is either the sha for the images up at quay.io/redhat-appstudio or 'dev' if you are using
# the jmv-build-service `make dev` flow.
KUBECONFIG=$KCP_KUBECONFIG helm install --set quayRespository=$QUAY_USERNAME --set quayToken=$QUAY_TOKEN --set quayTag=$QUAY_TAG $DIR/kcp-hacbs-workspace-init --debug --generate-name

#!/bin/sh

DIR=`dirname $0`

KUBECONFIG=$KCP_KUBECONFIG oc ws hacbs
KUBECONFIG=$KCP_KUBECONFIG oc create ns $HACBS_WORKSPACE_NAMESPACE
KUBECONFIG=$KCP_KUBECONFIG oc project $HACBS_WORKSPACE_NAMESPACE

EXISTING=$(KUBECONFIG=$KCP_KUBECONFIG helm list | grep kcp-hacbs-workspace-init)

if [[ ! -z $EXISTING ]]
then
 KUBECONFIG=$KCP_KUBECONFIG helm uninstall $EXISTING
fi

#KCP_KUBECONFIG should match whatever you have set in your infra-deployments preview.env file
#QUAY_USERNAME and QUAY_TOKEN are the same env's you use in the other dev flow scripts
#QUAY_TAG is either the sha for the images up at quay.io/redhat-appstudio or 'dev' if you are using
# the jmv-build-service `make dev` flow.
KUBECONFIG=$KCP_KUBECONFIG helm install --set quayJvmBuildServiceRepo=$QUAY_USERNAME --set quayTestRepoOwner=$QUAY_E2E_ORGANIZATION --set quayToken=$QUAY_TOKEN --set quayJvmBuildServiceTag=$QUAY_TAG $DIR/kcp-hacbs-workspace-init --debug --generate-name

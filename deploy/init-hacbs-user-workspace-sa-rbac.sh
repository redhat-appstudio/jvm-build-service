#!/bin/sh

EXISTING=$(KUBECONFIG=$CLUSTER_KUBECONFIG helm list | grep kcp-hacbs-workspace-compute-cluster-rbac)

if [[ ! -z $EXISTING ]]
then
 KUBECONFIG=$CLUSTER_KUBECONFIG helm uninstall $EXISTING
fi

DIR=`dirname $0`
#CLUSTER_KUBECONFIG should match whatever you have set in your infra-deployments preview.env file
#HABCS_WORKSPACE_NAMESPACE is the name of whatever namespace in the KCP hacbs ws that you ran init-hacbs-user-workspace.sh from
KUBECONFIG=$CLUSTER_KUBECONFIG helm install --set kcpNamespace=$HACBS_WORKSPACE_NAMESPACE $DIR/kcp-hacbs-workspace-compute-cluster-rbac --debug --generate-name

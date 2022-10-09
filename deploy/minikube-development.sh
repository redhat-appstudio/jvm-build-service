#!/bin/sh

DIR=`dirname $0`
kubectl apply -f https://github.com/tektoncd/pipeline/releases/download/v0.34.1/release.yaml
while ! oc get pods -n tekton-pipelines | grep tekton-pipelines-controller | grep Running; do
    sleep 1
done

$DIR/base-development.sh  $1

# base-development.sh switches to the test-jvm-namespace namespace
kubectl create sa pipeline
cat <<EOF | kubectl apply -f -
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: pipeline
  labels:
    rbac.authorization.k8s.io/aggregate-to-edit: "true"
rules:
  - apiGroups:
      - jvmbuildservice.io
    resources:
      - artifactbuilds
    verbs:
      - create
  - apiGroups:
      - ""
    resources:
      - configmaps
    resourceNames:
      - jvm-build-config
    verbs:
      - get
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: pipeline
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: pipeline
subjects:
  - kind: ServiceAccount
    name: pipeline
    namespace: test-jvm-namespace
EOF

#minikube cannot access registry.redhat.io by default
#you need to have these credentials in your docker config
kubectl create secret docker-registry minikube-pull-secret --from-file=.dockerconfigjson=$HOME/.docker/config.json
kubectl patch serviceaccount pipeline -p '{"imagePullSecrets": [{"name": "minikube-pull-secret"}]}'
kubectl patch serviceaccount default -p '{"imagePullSecrets": [{"name": "minikube-pull-secret"}]}'
kubectl apply -f https://raw.githubusercontent.com/openshift/api/master/quota/v1/0000_03_quota-openshift_01_clusterresourcequota.crd.yaml

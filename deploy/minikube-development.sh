#!/bin/bash

DIR=`dirname $0`
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/previous/v0.59.2/release.yaml
timeout=600 #10 minutes in seconds
endTime=$(( $(date +%s) + timeout ))

echo -e "\033[0;32mWaiting for Tekton Pipeines to start...\033[0m"
while ! oc get pods -n tekton-pipelines | grep tekton-pipelines-controller | grep "1/1"; do
    sleep 1
    if [ $(date +%s) -gt $endTime ]; then
        exit 1
    fi
done
while ! oc get pods -n tekton-pipelines | grep tekton-pipelines-webhook | grep "1/1"; do
    sleep 1
    if [ $(date +%s) -gt $endTime ]; then
        exit 1
    fi
done
#we need to make sure the tekton webhook has its rules installed
kubectl wait --for=jsonpath='{.webhooks[0].rules}' --timeout=300s mutatingwebhookconfigurations.admissionregistration.k8s.io webhook.pipeline.tekton.dev
kubectl patch cm feature-flags -n tekton-pipelines -p '{"data":{"enable-api-fields":"alpha"}}'
echo -e "\033[0;32mTekton controller is running\033[0m"

#CRDS are sometimes racey
kubectl apply -k $DIR/crds/base
# TODO: Don't think this below CRD is needed.
#Load missing CRD
#kubectl apply -f https://raw.githubusercontent.com/prometheus-operator/prometheus-operator/v0.69.1/example/prometheus-operator-crd/monitoring.coreos.com_servicemonitors.yaml
## Up to commit https://github.com/openshift/api/commit/60b796dbf3a2d90b6960bb04585a9f9289b0ca1f the flie has been changed, renamed and finally moved
## kubectl apply -f https://raw.githubusercontent.com/openshift/api/master/quota/v1/0000_03_quota-openshift_01_clusterresourcequota.crd.yaml
kubectl apply -f https://raw.githubusercontent.com/openshift/api/master/quota/v1/zz_generated.crd-manifests/0000_03_config-operator_01_clusterresourcequotas.crd.yaml
sleep 2

$DIR/base-development.sh $1

# base-development.sh switches to the test-jvm-namespace namespace
if [ -z "$JBS_WORKER_NAMESPACE" ]; then
    export JBS_WORKER_NAMESPACE=test-jvm-namespace
fi
cat $DIR/minikube-rbac.yaml | envsubst '${JBS_WORKER_NAMESPACE}' | kubectl apply -f -

# TODO: Unknown if we still need these.
#minikube cannot access registry.redhat.io by default
#you need to have these credentials in your docker config
#kubectl create --dry-run=client -o=yaml secret docker-registry minikube-pull-secret --from-file=.dockerconfigjson=$HOME/.docker/config.json | kubectl apply -f -
#kubectl patch serviceaccount pipeline -p '{"imagePullSecrets": [{"name": "minikube-pull-secret"}]}'
#kubectl patch serviceaccount default -p '{"imagePullSecrets": [{"name": "minikube-pull-secret"}]}'
#kubectl patch --type=merge jbsconfigs.jvmbuildservice.io jvm-build-config -p '{"spec":{"cacheSettings":{"disableTLS": true}}}'


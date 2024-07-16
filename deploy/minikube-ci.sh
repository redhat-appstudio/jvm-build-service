#!/bin/sh

DIR=`dirname $0`
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/previous/v0.56.5/release.yaml
timeout=600 #10 minutes in seconds
endTime=$(( $(date +%s) + timeout ))

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
echo "Tekton controller is running"

#CRDS are sometimes racey
kubectl apply -k $DIR/crds/base
kubectl apply -f https://raw.githubusercontent.com/openshift/api/master/quota/v1/0000_03_quota-openshift_01_clusterresourcequota.crd.yaml
sleep 2

kubectl delete --ignore-not-found deployments.apps hacbs-jvm-operator -n jvm-build-service
kubectl delete --ignore-not-found deployments.apps jvm-build-workspace-artifact-cache

echo "Using QUAY_USERNAME: $QUAY_USERNAME"
export JBS_WORKER_NAMESPACE=test-jvm-namespace
export JBS_QUAY_IMAGE=$QUAY_USERNAME
export JVM_BUILD_SERVICE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-controller
# Represents an empty dockerconfig.json
export JBS_BUILD_IMAGE_SECRET="ewogICAgImF1dGhzIjogewogICAgfQp9Cg==" # notsecret
export JBS_S3_SYNC_ENABLED="\"false\""
export JBS_MAX_MEMORY=4096

cat $DIR/base/namespace/namespace.yaml | envsubst '${JBS_WORKER_NAMESPACE}' | kubectl apply -f -
kubectl config set-context --current --namespace=test-jvm-namespace

#huge hack to deal with minikube local images, make sure they are never pulled
find $DIR -path \*dev-template\*.yaml -exec sed -i s/Always/Never/ {} \;

kustomize build $DIR/overlays/dev-template | envsubst '
${AWS_ACCESS_KEY_ID}
${AWS_PROFILE}
${AWS_SECRET_ACCESS_KEY}
${GIT_DEPLOY_IDENTITY}
${GIT_DEPLOY_TOKEN}
${GIT_DEPLOY_URL}
${GIT_DISABLE_SSL_VERIFICATION}
${JBS_BUILD_IMAGE_SECRET}
${JBS_GIT_CREDENTIALS}
${JBS_QUAY_IMAGE}
${JBS_MAX_MEMORY}
${JBS_RECIPE_DATABASE}
${JBS_S3_SYNC_ENABLED}
${JBS_WORKER_NAMESPACE}
${MAVEN_PASSWORD}
${MAVEN_REPOSITORY}
${MAVEN_USERNAME}
${QUAY_USERNAME}' \
    | kubectl apply -f -

echo "Completed overlays"
#this tells JBS we are in test mode and won't have a secure registry
kubectl annotate --overwrite jbsconfigs.jvmbuildservice.io --all jvmbuildservice.io/test-registry=true

kubectl create sa pipeline
kubectl apply -f $DIR/minikube-rbac.yaml

kubectl delete namespace test-jvm-namespace

#revert hack above to avoid edits in place
find $DIR -path \*dev-template\*.yaml -exec sed -i s/Never/Always/ {} \;

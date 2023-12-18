#!/bin/sh

if ! command -v kubectl &> /dev/null; then
    echo "Install kubectl from https://kubernetes.io/docs/tasks/tools/install-kubectl-linux"
    exit 1
fi
if ! command -v kustomize &> /dev/null; then
    echo "Install kustomize from https://kubectl.docs.kubernetes.io/installation/kustomize/binaries"
    exit 1
fi

if [ -z "$JBS_QUAY_IMAGE" ]; then
    export JBS_QUAY_IMAGE=redhat-appstudio
fi
if [ -z "$JBS_WORKER_NAMESPACE" ]; then
    export JBS_WORKER_NAMESPACE=test-jvm-namespace
fi

kubectl delete --ignore-not-found deployments.apps hacbs-jvm-operator -n jvm-build-service
# we don't restart the cache and local storage by default
# for most cases in development this is not necessary, and just slows things
# down by needing things to be re-cached/rebuilt

function cleanAllArtifacts() {
     kubectl delete --ignore-not-found namespaces $JBS_WORKER_NAMESPACE
}

kubectl delete --ignore-not-found deployments.apps jvm-build-workspace-artifact-cache
if [ "$1" = "--clean" ]; then
    cleanAllArtifacts
fi

echo -e "\033[0;32mSetting context to $JBS_WORKER_NAMESPACE with quay image $JBS_QUAY_IMAGE\033[0m"
# Its possible to set context before namespaces have been created.
kubectl config set-context --current --namespace=$JBS_WORKER_NAMESPACE


DIR=`dirname $0`

echo -e "\033[0;32mPatching...\033[0m"
JVM_BUILD_SERVICE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-controller \
JVM_BUILD_SERVICE_CACHE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-cache \
JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-build-request-processor:dev \
JVM_BUILD_SERVICE_CONSOLE_IMAGE=quay.io/$QUAY_USERNAME/jbs-management-console \
$DIR/patch-yaml.sh
echo -e "\033[0;32mRunning kubectl...\033[0m"
kustomize build $DIR/overlays/development | envsubst '${AWS_ACCESS_KEY_ID},${AWS_PROFILE},${AWS_SECRET_ACCESS_KEY},${GIT_DEPLOY_IDENTITY},${GIT_DEPLOY_TOKEN},${GIT_DEPLOY_URL},${GIT_DISABLE_SSL_VERIFICATION},${JBS_QUAY_IMAGE},${JBS_BUILD_IMAGE_SECRET},${JBS_GIT_CREDENTIALS},${JBS_WORKER_NAMESPACE},${MAVEN_PASSWORD},${MAVEN_USERNAME},${MAVEN_REPOSITORY},${QUAY_USERNAME}' | kubectl apply -f -

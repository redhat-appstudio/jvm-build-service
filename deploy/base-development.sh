#!/bin/sh

if ! command -v kubectl &> /dev/null; then
    echo "Install kubectl from https://kubernetes.io/docs/tasks/tools/install-kubectl-linux"
    exit 1
fi
if ! command -v kustomize &> /dev/null; then
    echo "Install kustomize from https://kubectl.docs.kubernetes.io/installation/kustomize/binaries"
    exit 1
fi

if [ -z "$QUAY_USERNAME" ]; then
    echo "Set QUAY_USERNAME"
    exit 1
fi
if [ -z "$JBS_QUAY_IMAGE" ]; then
    export JBS_QUAY_IMAGE="$QUAY_USERNAME"
fi
if [ -z "$JBS_WORKER_NAMESPACE" ]; then
    export JBS_WORKER_NAMESPACE=test-jvm-namespace
fi
if [ -z "$JBS_BUILD_IMAGE_SECRET" ]; then
    # Represents an empty dockerconfig.json
    export JBS_BUILD_IMAGE_SECRET="ewogICAgImF1dGhzIjogewogICAgfQp9Cg==" # notsecret
fi
if [ -z "$JBS_S3_SYNC_ENABLED" ]; then
    export JBS_S3_SYNC_ENABLED=true
fi
if [ -z "$JBS_MAX_MEMORY" ]; then
    export JBS_MAX_MEMORY=4096
fi
# Horrendous hack to work around
# https://github.com/kubernetes-sigs/kustomize/issues/5124
# given an env var is a string (https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.23/)
# and if 'true' or 'false' is seen that is coerced to a bool which causes an issue
export JBS_S3_SYNC_ENABLED="\"$JBS_S3_SYNC_ENABLED\""

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

echo -e "\033[0;32mRunning kustomize/kubectl...\033[0m"
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

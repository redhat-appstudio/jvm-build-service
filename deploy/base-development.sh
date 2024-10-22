#!/bin/bash

if ! command -v kubectl &> /dev/null; then
    echo "Install kubectl from https://kubernetes.io/docs/tasks/tools/install-kubectl-linux"
    exit 1
fi
if ! command -v kustomize &> /dev/null; then
    echo "Install kustomize from https://kubectl.docs.kubernetes.io/installation/kustomize/binaries"
    exit 1
fi

if [ -z "$JBS_QUAY_ORG" ]; then
    # Only if JBS_QUAY_ORG is not set is QUAY_USERNAME then required.
    if [ -z "$QUAY_USERNAME" ]; then
        echo "Set QUAY_USERNAME"
        exit 1
    fi
    export JBS_QUAY_ORG="$QUAY_USERNAME"
fi
if [ -z "$JBS_QUAY_IMAGE_CONTROLLER" ]; then
    export JBS_QUAY_IMAGE_CONTROLLER=hacbs-jvm-controller
fi
if [ -z "$JBS_QUAY_IMAGE_TAG" ]; then
    export JBS_QUAY_IMAGE_TAG=dev
fi
if [ -z "$JBS_WORKER_NAMESPACE" ]; then
    export JBS_WORKER_NAMESPACE=test-jvm-namespace
fi
if [ -z "$JBS_BUILD_IMAGE_SECRET" ]; then
    # Represents an empty dockerconfig.json
    export JBS_BUILD_IMAGE_SECRET="ewogICAgImF1dGhzIjogewogICAgfQp9Cg==" # notsecret
fi
if [ -z "$JBS_MAX_MEMORY" ]; then
    export JBS_MAX_MEMORY=4096
fi

function cleanAllArtifacts() {
    # Following are created in CI code
    kubectl delete --ignore-not-found=true tasks.tekton.dev git-clone
    kubectl delete --ignore-not-found=true tasks.tekton.dev maven
    kubectl delete --ignore-not-found=true pipelines.tekton.dev sample-component-build
    kubectl delete --ignore-not-found=true clusterrolebindings.rbac.authorization.k8s.io pipeline-${JBS_WORKER_NAMESPACE}
    kubectl delete --ignore-not-found=true deployments.apps jvm-build-maven-repo -n ${JBS_WORKER_NAMESPACE}

    kubectl delete --ignore-not-found=true artifactbuilds.jvmbuildservice.io --all

    kubectl delete --ignore-not-found=true pipelineruns.tekton.dev --all --wait=false
    kubectl delete --ignore-not-found=true taskruns.tekton.dev --all --wait=false
}

echo -e "\033[0;32mSetting context to $JBS_WORKER_NAMESPACE with quay image $JBS_QUAY_ORG\033[0m"
# Its possible to set context before namespaces have been created.
kubectl config set-context --current --namespace=$JBS_WORKER_NAMESPACE
kubectl delete --ignore-not-found deployments.apps hacbs-jvm-operator -n jvm-build-service
kubectl delete --ignore-not-found deployments.apps jvm-build-workspace-artifact-cache


if [ "$1" = "--clean" ]; then
    cleanAllArtifacts
fi

DIR=`dirname $0`

echo -e "\033[0;32mRunning kustomize/kubectl...\033[0m"
kustomize build $DIR/overlays/dev-template | envsubst '
${GIT_DEPLOY_IDENTITY}
${GIT_DEPLOY_TOKEN}
${GIT_DEPLOY_URL}
${GIT_DISABLE_SSL_VERIFICATION}
${JBS_BUILD_IMAGE_SECRET}
${JBS_GIT_CREDENTIALS}
${JBS_QUAY_IMAGE_CONTROLLER}
${JBS_QUAY_IMAGE_TAG}
${JBS_QUAY_ORG}
${JBS_MAX_MEMORY}
${JBS_RECIPE_DATABASE}
${JBS_WORKER_NAMESPACE}
${MAVEN_PASSWORD}
${MAVEN_REPOSITORY}
${MAVEN_USERNAME}
${QUAY_USERNAME}' \
    | kubectl apply -f -

echo "Completed overlays"

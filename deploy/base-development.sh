#!/bin/sh

kubectl delete --ignore-not-found deployments.apps hacbs-jvm-operator -n jvm-build-service
# we don't restart the cache and local storage by default
# for most cases in development this is not necessary, and just slows things
# down by needing things to be re-cached/rebuilt

function cleanAllArtifacts() {
     kubectl delete --ignore-not-found namespaces test-jvm-namespace
}

kubectl delete --ignore-not-found deployments.apps jvm-build-workspace-artifact-cache
if [ "$1" = "--clean" ]; then
    cleanAllArtifacts
fi

DIR=`dirname $0`
kubectl apply -f $DIR/namespace.yaml
kubectl config set-context --current --namespace=test-jvm-namespace

echo -e "\033[0;32mSecrets...\033[0m"
kubectl create --dry-run=client -o=yaml secret generic jvm-build-image-secrets --from-file=.dockerconfigjson=$HOME/.docker/config.json --type=kubernetes.io/dockerconfigjson | kubectl apply -f -
kubectl create --dry-run=client -o=yaml secret generic jvm-build-git-secrets --from-literal .git-credentials="
https://$GITHUB_E2E_ORGANIZATION:$GITHUB_TOKEN@github.com
https://test:test@gitlab.com
" | kubectl apply -f -
if [ -n "$GIT_DEPLOY_TOKEN" ]; then
    kubectl create --dry-run=client -o=yaml secret generic jvm-build-git-repo-secrets --from-literal gitdeploytoken="$GIT_DEPLOY_TOKEN" | kubectl apply -f -
fi
if [ -n "$MAVEN_PASSWORD" ]; then
    kubectl create --dry-run=client -o=yaml secret generic jvm-build-maven-repo-secrets --from-literal mavenpassword="$MAVEN_PASSWORD" | kubectl apply -f -
fi
if [ -n "$AWS_ACCESS_KEY_ID" ] && [ -n "$AWS_SECRET_ACCESS_KEY" ]; then
    if [ -n "$AWS_PROFILE" ]; then
        PROFILE="--from-literal awsprofile=$AWS_PROFILE"
    fi
    kubectl create --dry-run=client -o=yaml secret generic jvm-build-maven-repo-aws-secrets --from-literal=awsaccesskey=$AWS_ACCESS_KEY_ID --from-literal awssecretkey="$AWS_SECRET_ACCESS_KEY" $PROFILE | kubectl apply -f -
    kubectl create --dry-run=client -o=yaml secret generic jvm-build-s3-secrets --from-literal=awsaccesskey=$AWS_ACCESS_KEY_ID --from-literal awssecretkey="$AWS_SECRET_ACCESS_KEY" --from-literal awsregion=us-east-1 | kubectl apply -f -
fi

echo -e "\033[0;32mPatching...\033[0m"
JVM_BUILD_SERVICE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-controller \
JVM_BUILD_SERVICE_CACHE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-cache \
JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-build-request-processor:dev \
JVM_BUILD_SERVICE_CONSOLE_IMAGE=quay.io/$QUAY_USERNAME/jbs-management-console \
$DIR/patch-yaml.sh
echo -e "\033[0;32mRunning kubectl...\033[0m"
kubectl apply -k $DIR/overlays/development

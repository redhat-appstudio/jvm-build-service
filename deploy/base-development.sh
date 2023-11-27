#!/bin/sh

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

#echo -e "\033[0;32mSecrets...\033[0m"
# kubectl create --dry-run=client -o=yaml secret generic jvm-build-image-secrets --from-file=.dockerconfigjson=$HOME/.docker/config.json --type=kubernetes.io/dockerconfigjson | kubectl apply -f -
# kubectl create --dry-run=client -o=yaml secret generic jvm-build-git-secrets --from-literal .git-credentials="
# https://$GITHUB_E2E_ORGANIZATION:$GITHUB_TOKEN@github.com
# https://test:test@gitlab.com
# " | kubectl apply -f -
# if [ -n "$GIT_DEPLOY_TOKEN" ]; then
#     kubectl create --dry-run=client -o=yaml secret generic jvm-build-git-repo-secrets --from-literal gitdeploytoken="$GIT_DEPLOY_TOKEN" | kubectl apply -f -
# fi
# if [ -n "$MAVEN_PASSWORD" ]; then
#     kubectl create --dry-run=client -o=yaml secret generic jvm-build-maven-repo-secrets --from-literal mavenpassword="$MAVEN_PASSWORD" | kubectl apply -f -
# fi
# if [ -n "$AWS_ACCESS_KEY_ID" ] && [ -n "$AWS_SECRET_ACCESS_KEY" ]; then
#     if [ -n "$AWS_PROFILE" ]; then
#         PROFILE="--from-literal awsprofile=$AWS_PROFILE"
#     fi
#     kubectl create --dry-run=client -o=yaml secret generic jvm-build-maven-repo-aws-secrets --from-literal=awsaccesskey=$AWS_ACCESS_KEY_ID --from-literal awssecretkey="$AWS_SECRET_ACCESS_KEY" $PROFILE | kubectl apply -f -
#     kubectl create --dry-run=client -o=yaml secret generic jvm-build-s3-secrets --from-literal=awsaccesskey=$AWS_ACCESS_KEY_ID --from-literal awssecretkey="$AWS_SECRET_ACCESS_KEY" --from-literal awsregion=us-east-1 | kubectl apply -f -
# fi

echo -e "\033[0;32mPatching...\033[0m"
JVM_BUILD_SERVICE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-controller \
JVM_BUILD_SERVICE_CACHE_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-cache \
JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE=quay.io/$QUAY_USERNAME/hacbs-jvm-build-request-processor:dev \
JVM_BUILD_SERVICE_CONSOLE_IMAGE=quay.io/$QUAY_USERNAME/jbs-management-console \
$DIR/patch-yaml.sh
echo -e "\033[0;32mRunning kubectl...\033[0m"
kustomize build $DIR/overlays/development | envsubst '${AWS_ACCESS_KEY_ID},${AWS_PROFILE},${AWS_SECRET_ACCESS_KEY},${GIT_DEPLOY_IDENTITY},${GIT_DEPLOY_TOKEN},${GIT_DEPLOY_URL},${GIT_DISABLE_SSL_VERIFICATION},${JBS_QUAY_IMAGE},${JBS_BUILD_IMAGE_SECRET},${JBS_GIT_CREDENTIALS},${JBS_WORKER_NAMESPACE},${MAVEN_PASSWORD},${MAVEN_USERNAME},${MAVEN_REPOSITORY},${QUAY_USERNAME}' | kubectl apply -f -

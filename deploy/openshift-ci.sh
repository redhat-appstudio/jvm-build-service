#!/bin/bash
#
# This is referenced by:
# https://github.com/openshift/release/blob/master/ci-operator/config/redhat-appstudio/jvm-build-service/redhat-appstudio-jvm-build-service-main.yaml
#
echo "Executing openshift-ci.sh"

echo "jvm build service golang operator image:"
echo ${JVM_BUILD_SERVICE_IMAGE}
echo "jvm build service jvm cache image:"
echo ${JVM_BUILD_SERVICE_CACHE_IMAGE}
echo "jvm build service jvm reqprocessor image:"
echo ${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}
echo "jvm build service jvm domain proxy image:"
echo ${JVM_BUILD_SERVICE_DOMAIN_PROXY_IMAGE}

function waitFor() {
    endTime=$(( $(date +%s) + 600 ))
    while ! eval $1; do
        sleep 1
        if [ $(date +%s) -gt $endTime ]; then
            echo "Unable to find $1"
            exit 1
        fi
    done
}

DIR=`dirname $0`
echo "Running out of ${DIR}"

oc apply -f ${DIR}/base/pipelines/openshift-pipelines-subscription.yaml
waitFor "oc get ns openshift-pipelines"
waitFor "oc get pods -n openshift-pipelines | grep tekton-pipelines-controller | grep Running"
waitFor "oc get mutatingwebhookconfigurations.admissionregistration.k8s.io webhook.pipeline.tekton.dev -o yaml | grep rules"
oc patch tektonconfigs.operator.tekton.dev config --type=merge -p '{"spec":{"pipeline":{"enable-api-fields":"alpha"}}}'
echo "Tekton controller is running"

oc create ns jvm-build-service || true
oc apply -k $DIR/crds/base
oc apply -k $DIR/operator/base
oc apply -k $DIR/operator/config
find $DIR -name ci-final -exec rm -r {} \;
find $DIR -name ci-template -exec cp -r {} {}/../ci-final \;
# copy deployment yaml, but change the image placeholder, as we employ a simpler/basic substution via env vars in openshift ci
sed 's/image: hacbs-jvm-operator:next/image: jvm-build-service-image/' $DIR/operator/base/deployment.yaml > $DIR/operator/overlays/ci-final/base-deployment.yaml
find $DIR -path \*ci-final\*.yaml -exec sed -i s%jvm-build-service-image%${JVM_BUILD_SERVICE_IMAGE}% {} \;
# TODO: I don't think the below is used.
# find $DIR -path \*ci-final\*.yaml -exec sed -i s%jvm-build-service-reqprocessor-image%${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}% {} \;
find $DIR -path \*ci-final\*.yaml -exec sed -i s/ci-template/ci-final/ {} \;
oc apply -k $DIR/operator/overlays/ci-final
oc set env deployment/hacbs-jvm-operator -n jvm-build-service \
JVM_BUILD_SERVICE_IMAGE=${JVM_BUILD_SERVICE_IMAGE} \
JVM_BUILD_SERVICE_CACHE_IMAGE=${JVM_BUILD_SERVICE_CACHE_IMAGE} \
JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE=${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}

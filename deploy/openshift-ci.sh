#!/bin/sh

echo "Executing openshift-ci.sh"

echo "jvm build service golang operator image:"
echo ${JVM_BUILD_SERVICE_IMAGE}
echo "jvm build service jvm cache image:"
echo ${JVM_BUILD_SERVICE_CACHE_IMAGE}
echo "jvm build service jvm analyzer image:"
echo ${JVM_BUILD_SERVICE_ANALYZER_IMAGE}
echo "jvm build service jvm reqprocessor image:"
echo ${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}

DIR=`dirname $0`
echo "Running out of ${DIR}"
$DIR/install-openshift-pipelines.sh
oc create ns jvm-build-service || true
oc apply -k $DIR/crds/base
oc apply -k $DIR/operator/base
oc apply -k $DIR/operator/config
find $DIR -name ci-final -exec rm -r {} \;
find $DIR -name ci-template -exec cp -r {} {}/../ci-final \;
# copy deployment yaml, but change the image placeholder, as we employ a simpler/basic substution via env vars in openshift ci
sed 's/image: hacbs-jvm-operator/image: jvm-build-service-image/' $DIR/operator/base/deployment.yaml > $DIR/operator/overlays/ci-final/base-deployment.yaml
find $DIR -path \*ci-final\*.yaml -exec sed -i s%jvm-build-service-image%${JVM_BUILD_SERVICE_IMAGE}% {} \;
find $DIR -path \*ci-final\*.yaml -exec sed -i s%jvm-build-service-reqprocessor-image%${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}% {} \;
find $DIR -path \*ci-final\*.yaml -exec sed -i s/ci-template/ci-final/ {} \;
oc apply -k $DIR/operator/overlays/ci-final
oc set env deployment/hacbs-jvm-operator -n jvm-build-service \
JVM_BUILD_SERVICE_IMAGE=${JVM_BUILD_SERVICE_IMAGE} \
JVM_BUILD_SERVICE_CACHE_IMAGE=${JVM_BUILD_SERVICE_CACHE_IMAGE} \
JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE=${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE} \
JVM_BUILD_SERVICE_ANALYZER_IMAGE=${JVM_BUILD_SERVICE_ANALYZER_IMAGE}

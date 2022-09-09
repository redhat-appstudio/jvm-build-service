#!/bin/sh

echo "Executing openshift-ci.sh"

echo "jvm build service golang operator image:"
echo ${JVM_BUILD_SERVICE_IMAGE}
echo "jvm build service jvm cache image:"
echo ${JVM_BUILD_SERVICE_CACHE_IMAGE}
echo "jvm build service jvm sidecar image:"
echo ${JVM_BUILD_SERVICE_SIDECAR_IMAGE}
echo "jvm build service jvm analyzer image:"
echo ${JVM_BUILD_SERVICE_ANALYZER_IMAGE}
echo "jvm build service jvm reqprocessor image:"
echo ${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}

DIR=`dirname $0`
echo "Running out of ${DIR}"
$DIR/install-openshift-pipelines.sh
oc create ns jvm-build-service || true
find $DIR -name ci-final -exec rm -r {} \;
find $DIR -name ci-template -exec cp -r {} {}/../ci-final \;
# copy this over since if updates more frequently
cp $DIR/base/system-config.yaml $DIR/overlays/ci-final/system-config-builders.yaml
find $DIR -path \*ci-final\*.yaml -exec sed -i s%jvm-build-service-image%${JVM_BUILD_SERVICE_IMAGE}% {} \;
find $DIR -path \*ci-final\*.yaml -exec sed -i s%jvm-build-service-cache-image%${JVM_BUILD_SERVICE_CACHE_IMAGE}% {} \;
find $DIR -path \*ci-final\*.yaml -exec sed -i s%jvm-build-service-sidecar-image%${JVM_BUILD_SERVICE_SIDECAR_IMAGE}% {} \;
find $DIR -path \*ci-final\*.yaml -exec sed -i s%jvm-build-service-analyzer-image%${JVM_BUILD_SERVICE_ANALYZER_IMAGE}% {} \;
find $DIR -path \*ci-final\*.yaml -exec sed -i s%jvm-build-service-reqprocessor-image%${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}% {} \;
find $DIR -path \*ci-final\*.yaml -exec sed -i s/ci-template/ci-final/ {} \;
oc apply -k $DIR/crds/base
oc apply -f $DIR/operator/base/sa.yaml
oc apply -f $DIR/operator/base/rbac.yaml
oc apply -k $DIR/operator/overlays/ci-final
oc apply -k $DIR/overlays/ci-final

#!/bin/sh

# this is assumes it is called from development.sh or openshift-ci.sh where those scripts have set
# all the JVM_BUILD_SERVICE... env vars

echo "Executing patch-yaml.sh"

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
echo "jvm build service JDK8 builder image:"
echo ${JVM_BUILD_SERVICE_JDK8_BUILDER_IMAGE}
echo "jvm build service JDK11 builder image:"
echo ${JVM_BUILD_SERVICE_JDK11_BUILDER_IMAGE}
echo "jvm build service JDK17 builder image:"
echo ${JVM_BUILD_SERVICE_JDK17_BUILDER_IMAGE}



DIR=`dirname $0`
find $DIR -name development -exec rm -r {} \;
find $DIR -name dev-template -exec cp -r {} {}/../development \;
find $DIR -path \*development\*.yaml -exec sed -i s%jvm-build-service-image%${JVM_BUILD_SERVICE_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec sed -i s%jvm-build-service-cache-image%${JVM_BUILD_SERVICE_CACHE_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec sed -i s%jvm-build-service-sidecar-image%${JVM_BUILD_SERVICE_SIDECAR_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec sed -i s%jvm-build-service-analyzer-image%${JVM_BUILD_SERVICE_ANALYZER_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec sed -i s%jvm-build-service-reqprocessor-image%${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec sed -i s%jdk8-builder%${JVM_BUILD_SERVICE_JDK8_BUILDER_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec sed -i s%jdk11-builder%${JVM_BUILD_SERVICE_JDK11_BUILDER_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec sed -i s%jdk17-builder%${JVM_BUILD_SERVICE_JDK17_BUILDER_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec sed -i s/dev-template/development/ {} \;
find $DIR -path \*development\*.yaml -exec sed -i s/QUAY_TOKEN/${QUAY_TOKEN}/ {} \;
find $DIR -path \*development\*.yaml -exec sed -i s/QUAY_USERNAME/${QUAY_USERNAME}/ {} \;

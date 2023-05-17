#!/bin/sh

# this is assumes it is called from development.sh or openshift-ci.sh where those scripts have set
# all the JVM_BUILD_SERVICE... env vars

echo "Executing patch-yaml.sh"

echo "jvm build service golang operator image:"
echo ${JVM_BUILD_SERVICE_IMAGE}
echo "jvm build service jvm cache image:"
echo ${JVM_BUILD_SERVICE_CACHE_IMAGE}
echo "jvm build service jvm reqprocessor image:"
echo ${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}

SED=sed
if [ "$(uname)" = "Darwin" ]; then
    SED=gsed
fi

DIR=`dirname $0`
rm -rf $DIR/operator/overlays/development $DIR/overlays/development
find $DIR -name dev-template -exec cp -r {} {}/../development \;
find $DIR -path \*development\*.yaml -exec $SED -i s%jvm-build-service-image%${JVM_BUILD_SERVICE_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s%jvm-build-service-cache-image%${JVM_BUILD_SERVICE_CACHE_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s%jvm-build-service-reqprocessor-image%${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s/dev-template/development/ {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s/QUAY_TOKEN/${QUAY_TOKEN}/ {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s/QUAY_USERNAME/${QUAY_USERNAME}/ {} \;

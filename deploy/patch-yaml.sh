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
echo "jvm build service jvm console image:"
echo ${JVM_BUILD_SERVICE_CONSOLE_IMAGE}

SED=sed
if [ "$(uname)" = "Darwin" ]; then
    SED=gsed
fi

DIR=`dirname $0`
rm -rf $DIR/operator/overlays/development $DIR/overlays/development $DIR/console/overlays/development
find $DIR -name dev-template -exec cp -r {} {}/../development \;
find $DIR -path \*development\*.yaml -exec $SED -i s%jvm-build-service-image%${JVM_BUILD_SERVICE_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s%jvm-build-service-cache-image%${JVM_BUILD_SERVICE_CACHE_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s%jvm-build-service-reqprocessor-image%${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE}% {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s/dev-template/development/ {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s/QUAY_TOKEN/${QUAY_TOKEN}/ {} \;

if [ ! -z "${JVM_BUILD_SERVICE_CONSOLE_IMAGE}" ]; then
    find $DIR -path \*development\*.yaml -exec $SED -i s%jvm-build-service-console-image%${JVM_BUILD_SERVICE_CONSOLE_IMAGE}% {} \;
fi


if [ -z "${MAVEN_USERNAME}" ]; then
    MAVEN_USERNAME=""
fi
if [ -z "${MAVEN_REPOSITORY}" ]; then
    MAVEN_REPOSITORY=""
fi

find $DIR -path \*development\*.yaml -exec $SED -i s/MAVEN_USERNAME/${MAVEN_USERNAME}/ {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s%MAVEN_REPOSITORY%${MAVEN_REPOSITORY}% {} \;
#if [ -n "$QUAY_TOKEN" ]; then
#    $SED -i '/owner: QUAY_USERNAME/d' $DIR/overlays/development/config.yaml
#fi
find $DIR -path \*development\*.yaml -exec $SED -i s/QUAY_USERNAME/${QUAY_USERNAME}/ {} \;

if [ -z "${GIT_DEPLOY_URL}" ]; then
    GIT_DEPLOY_URL=""
fi
if [ -z "${GIT_DEPLOY_IDENTITY}" ]; then
    GIT_DEPLOY_IDENTITY=""
fi
if [ -z "${GIT_DISABLE_SSL_VERIFICATION}" ]; then
    GIT_DISABLE_SSL_VERIFICATION="false"
fi
find $DIR -path \*development\*.yaml -exec $SED -i s%GIT_DEPLOY_URL%${GIT_DEPLOY_URL}% {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s%GIT_DEPLOY_IDENTITY%${GIT_DEPLOY_IDENTITY}% {} \;
find $DIR -path \*development\*.yaml -exec $SED -i s%GIT_DISABLE_SSL_VERIFICATION%${GIT_DISABLE_SSL_VERIFICATION}% {} \;

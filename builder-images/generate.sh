#!/bin/bash

set -eu

DIR=`dirname $0`
generate () {

  export IMAGE_NAME=hacbs-jdk$JAVA-builder
  export BASE_IMAGE=registry.access.redhat.com/ubi8/openjdk-$JAVA
  mkdir -p $DIR/$IMAGE_NAME
  #deal with gradle
  gradle=`yq .data.\"builder-image.jdk$JAVA.tags\" $DIR/../deploy/base/system-config.yaml | grep -o -E  "gradle:.*,?" | cut -d : -f 2`
  echo $gradle
  export GRADLE_STRING=""
  for i in ${gradle//;/ }
  do
      export GRADLE_VERSION=$i
      export GRADLE_DOWNLOAD_SHA256=$(name=GRADLE_${GRADLE_VERSION//./_} && echo ${!name})
      res=`envsubst '$GRADLE_DOWNLOAD_SHA256,$GRADLE_VERSION' < $DIR/gradle.template`
      export GRADLE_STRING="$GRADLE_STRING $res"
  done
  export GRADLE_STRING="$GRADLE_STRING true"

  envsubst '$IMAGE_NAME,$BASE_IMAGE,$MAVEN_VERSION,$MAVEN_SHA,$GRADLE_VERSION,$GRADLE_SHA,$GRADLE_MANIPULATOR_VERSION,$CLI_JAR_SHA,$ANALYZER_INIT_SHA,$GRADLE_STRING' < $DIR/Dockerfile.template > $DIR/$IMAGE_NAME/Dockerfile
  envsubst '$IMAGE_NAME,$BASE_IMAGE' < $DIR/push.yaml > $DIR/../.tekton/$IMAGE_NAME-push.yaml
  envsubst '$IMAGE_NAME,$BASE_IMAGE' < $DIR/pull-request.yaml > $DIR/../.tekton/$IMAGE_NAME-pull-request.yaml
}

export MAVEN_VERSION=3.8.6
export MAVEN_SHA=f790857f3b1f90ae8d16281f902c689e4f136ebe584aba45e4b1fa66c80cba826d3e0e52fdd04ed44b4c66f6d3fe3584a057c26dfcac544a60b301e6d0f91c26
export GRADLE_MANIPULATOR_VERSION=3.7
export CLI_JAR_SHA=8af7e87638bb237362bf8f486b489c251e474be1cdc40037ba79887c2f0803b9
export ANALYZER_INIT_SHA=3172f34e126d652efccce50ca98c58f9baca27d89b5482268d0a4cb44023aa7e

export GRADLE_7_5_1=f6b8596b10cce501591e92f229816aa4046424f3b24d771751b06779d58c8ec4
export GRADLE_7_4_2=29e49b10984e585d8118b7d0bc452f944e386458df27371b49b4ac1dec4b7fda
export GRADLE_6_9_2=8b356fd8702d5ffa2e066ed0be45a023a779bba4dd1a68fd11bc2a6bdc981e8f
export GRADLE_5_6_4=1f3067073041bc44554d0efe5d402a33bc3d3c93cc39ab684f308586d732a80d
export GRADLE_4_10_3=8626cbf206b4e201ade7b87779090690447054bc93f052954c78480fa6ed186e

export JAVA=17
generate

export JAVA=8
generate

export JAVA=11
generate

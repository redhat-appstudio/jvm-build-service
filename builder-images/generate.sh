#!/bin/sh

set -eu

generate () {
  mkdir -p $IMAGE_NAME
  envsubst '$IMAGE_NAME,$BASE_IMAGE,$MAVEN_VERSION,$MAVEN_SHA,$GRADLE_VERSION,$GRADLE_SHA,$GRADLE_MANIPULATOR_VERSION,$CLI_JAR_SHA,$ANALYZER_INIT_SHA' < Dockerfile.template > $IMAGE_NAME/Dockerfile
  envsubst '$IMAGE_NAME,$BASE_IMAGE' < push.yaml > ../.tekton/$IMAGE_NAME-push.yaml
  envsubst '$IMAGE_NAME,$BASE_IMAGE' < pull-request.yaml > ../.tekton/$IMAGE_NAME-pull-request.yaml
}

export MAVEN_VERSION=3.8.6
export MAVEN_SHA=f790857f3b1f90ae8d16281f902c689e4f136ebe584aba45e4b1fa66c80cba826d3e0e52fdd04ed44b4c66f6d3fe3584a057c26dfcac544a60b301e6d0f91c26
export GRADLE_VERSION=7.5
export GRADLE_SHA=cb87f222c5585bd46838ad4db78463a5c5f3d336e5e2b98dc7c0c586527351c2
export GRADLE_MANIPULATOR_VERSION=3.7
export CLI_JAR_SHA=8af7e87638bb237362bf8f486b489c251e474be1cdc40037ba79887c2f0803b9
export ANALYZER_INIT_SHA=3172f34e126d652efccce50ca98c58f9baca27d89b5482268d0a4cb44023aa7e

export IMAGE_NAME=hacbs-jdk17-builder
export BASE_IMAGE=registry.access.redhat.com/ubi8/openjdk-17
generate


export IMAGE_NAME=hacbs-jdk8-builder
export BASE_IMAGE=registry.access.redhat.com/ubi8/openjdk-8
generate


export IMAGE_NAME=hacbs-jdk11-builder
export BASE_IMAGE=registry.access.redhat.com/ubi8/openjdk-11
generate

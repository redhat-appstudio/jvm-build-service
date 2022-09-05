#!/bin/sh

set -eu

docker push quay.io/$QUAY_USERNAME/hacbs-jdk8-builder:dev
docker push quay.io/$QUAY_USERNAME/hacbs-jdk17-builder:dev
docker push quay.io/$QUAY_USERNAME/hacbs-jdk11-builder:dev

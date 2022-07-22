#!/bin/sh

docker build hacbs-jdk8-builder -t quay.io/$QUAY_USERNAME/hacbs-jdk8-builder:dev
docker build hacbs-jdk17-builder -t quay.io/$QUAY_USERNAME/hacbs-jdk17-builder:dev
docker build hacbs-jdk11-builder -t quay.io/$QUAY_USERNAME/hacbs-jdk11-builder:dev
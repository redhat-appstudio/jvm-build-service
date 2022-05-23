#!/bin/sh

cekit --descriptor  builder-image.yaml build podman --no-squash
cekit --descriptor   builder-image.yaml build  --overrides-file jdk11.yaml podman --no-squash
cekit --descriptor  builder-image.yaml build  --overrides-file jdk17.yaml podman --no-squash

podman push quay.io/sdouglas/hacbs-jdk17-builder:latest
podman push quay.io/sdouglas/hacbs-jdk11-builder:latest
podman push quay.io/sdouglas/hacbs-jdk8-builder:latest
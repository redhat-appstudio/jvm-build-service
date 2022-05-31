#!/bin/sh

sed "s/QUAY_REPO/$1/" builder-image-template.yaml | sed "s/SNAPSHOT/$2/" >builder-image.generated.yaml
sed "s/QUAY_REPO/$1/" jdk11-template.yaml | sed "s/SNAPSHOT/$2/" >jdk11.generated.yaml
sed "s/QUAY_REPO/$1/" jdk17-template.yaml | sed "s/SNAPSHOT/$2/" >jdk17.generated.yaml

cekit --descriptor builder-image.generated.yaml build "$3" --no-squash
cekit --descriptor builder-image.generated.yaml build  --overrides-file jdk11.generated.yaml "$3" --no-squash
cekit --descriptor builder-image.generated.yaml build  --overrides-file jdk17.generated.yaml "$3" --no-squash

echo quay.io/$1/hacbs-jdk17-builder:$2
$3 push quay.io/$1/hacbs-jdk11-builder:$2
$3 push quay.io/$1/hacbs-jdk17-builder:$2
$3 push quay.io/$1/hacbs-jdk8-builder:$2
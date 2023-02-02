#!/bin/bash

set -e
set -o pipefail

git fetch origin
git checkout origin/main

DIR=$(dirname $0)
VER=$1
if [ -z "$VER" ]; then
    echo "Please supply a version"
    exit 1
fi

if [ -z "$(git status --untracked-files=no --porcelain)" ]; then
    find java-components -name pom.xml -exec sed -i s/999-SNAPSHOT/$VER/ {} \;
    git commit -a -m $VER
    git tag -a $VER -m $VER
    cd java-components && mvn clean install deploy -DskipTests
    git push origin $VER
    echo "Released"
else
    echo "Modified files, please release from a clean directory"
    exit 1
fi

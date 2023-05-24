#!/usr/bin/env bash
# Copies JDK17 into the system java directory, for use by the build request processor
cp -r /deployments build-request-processor
mkdir system-java
cp -RL  $JAVA_HOME/* system-java/

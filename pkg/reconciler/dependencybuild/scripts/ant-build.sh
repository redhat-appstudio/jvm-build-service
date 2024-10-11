#!/usr/bin/env bash

echo "Running $(which ant) with arguments: $@"
eval "ant $@" | tee $(workspaces.source.path)/logs/ant.log

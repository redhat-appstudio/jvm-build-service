#!/usr/bin/env bash
set -o verbose
set -eu
set -o pipefail

TASK="ip link set dev lo up && /workspace/source/build.sh $@"
unshare -n -Ufp -r --  sh -c "$TASK"

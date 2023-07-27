#!/usr/bin/env bash
set -o verbose
set -eu
set -o pipefail

TASK="ip link set dev lo up && /original-content/build.sh $@"
unshare -n -Ufp -r --  sh -c "$TASK"

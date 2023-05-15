#!/bin/bash
#
# This file is required for the diagnostic dockerfiles - see buildrecipeyaml.go

/opt/mdcat-2.0.3-x86_64-unknown-linux-musl/mdcat /root/README.md

echo ""

/bin/bash "$@"

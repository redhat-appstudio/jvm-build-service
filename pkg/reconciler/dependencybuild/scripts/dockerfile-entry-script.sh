#!/bin/bash
#
# This file is required for the diagnostic dockerfiles - see buildrecipeyaml.go


echo -e "Diagnostic docker files are supplied for each build. The Dockerfile is a self-contained unit that
allows the user to start a cache and then perform a build using the same methods as the Java Build
Service (https://github.com/redhat-appstudio/jvm-build-service) uses internally.

\e[1;32mTo perform a build, firstly run the following script to setup the cache:\e[0m

\e[1;33m./start-cache.sh\e[0m

\e[1;32mNext run (which may be run repeatedly if required):\e[0m

\e[1;33m./run-full-build.sh\e[0m
"

/bin/bash "$@"

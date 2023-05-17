
[//]: # (This file is required for the diagnostic dockerfiles - see buildrecipeyaml.go)

# Diagnostic Docker Image

Diagnostic docker files are supplied for each build. The Dockerfile is a self-contained unit that allows the user to start a cache and then perform a build using the same methods as the [Java Build Service](https://github.com/redhat-appstudio/jvm-build-service) uses internally.

## Performing a build

Firstly run the following script to setup the cache:

```
./start-cache.sh
```

Next run (which may be run repeatedly if required):

```
./run-full-build.sh
```

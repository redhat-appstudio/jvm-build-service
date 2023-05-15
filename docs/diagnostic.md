
[//]: # (This file is required for the diagnostic dockerfiles - see buildrecipeyaml.go)

# Diagnostic Docker Image

Diagnostic docker files are supplied for each build.

## Prerequisites

The Dockerfile is a self-contained unit that allows the user to start a cache and then perform a build using the same methods as the [Java Build Service](https://github.com/redhat-appstudio/jvm-build-service) uses internally.


## Building and running the image

To build the image, a standard build command e.g. `podman build .` (or `podman build -f <Dockerfile> .`) is sufficient.

Running the image as `podman run <image>` will output this README (which has also been placed at `/root/README.md`).

To use this image to run a build, run
```
podman run -it <image>
```
or, if the last image built was this one:
```
podman run -v -it $(podman images -n -q | head -1)
```

## Performing a build

Firstly run the following script to setup the cache:

```
./start-cache.sh
```

Next run (which may be run repeatedly if required):

```
./run-full-build.sh
```



# Task Definitions

Note that while `pre-build.yaml` and `maven-deployment.yaml` are created by our team the `buildah-oci-yaml` is a temporary copy from https://github.com/konflux-ci/build-definitions/blob/main/task/buildah-oci-ta/0.2/buildah-oci-ta.yaml.


## buildah-oci-ta

It should be base-lined to the most recent definition from Konflux build-definitions repository and then the following changes should be applied to that:

### Domain Proxy
Adds Domain Proxy to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L134-L189
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L288-L313
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L327
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L621-L657

### Indy Sidecar
Adds Indy configuration to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L190-L201
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L234-L244
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L872-L894


### Trusted CA
Adds the trusted ca to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L591-L595


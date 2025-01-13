

# Task Definitions

Note that while `pre-build.yaml` and `maven-deployment.yaml` are created by our team the `buildah-oci-yaml` is a temporary copy from https://github.com/konflux-ci/build-definitions/blob/main/task/buildah-oci-ta/0.2/buildah-oci-ta.yaml.


## buildah-oci-ta

It should be base-lined to the most recent definition from Konflux build-definitions repository and then the following changes should be applied to that:

### Domain Proxy
Adds Domain Proxy to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L142-L197
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L315-L340
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L354
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L646-L682

### Indy Sidecar
Adds Indy configuration to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L198-L209
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L242-L252
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L918-L940


### Trusted CA
Adds the trusted ca to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L616-L620


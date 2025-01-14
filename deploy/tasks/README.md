

# Task Definitions

Note that while `pre-build.yaml` and `maven-deployment.yaml` are created by our team the `buildah-oci-yaml` is a temporary copy from https://github.com/konflux-ci/build-definitions/blob/main/task/buildah-oci-ta/0.2/buildah-oci-ta.yaml.


## buildah-oci-ta

It should be base-lined to the most recent definition from Konflux build-definitions repository and then the following changes should be applied to that:

### Domain Proxy
Adds Domain Proxy to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L142-L197
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L309-L334
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L348
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L640-L676

### Indy Sidecar
Adds Indy configuration to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L198-L209
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L236-L246
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L882-L904


### Trusted CA
Adds the trusted ca to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L610-L614


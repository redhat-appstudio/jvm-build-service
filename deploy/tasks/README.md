

# Task Definitions

Note that while `pre-build.yaml` and `maven-deployment.yaml` are created by our team the `buildah-oci-yaml` is a temporary copy from https://github.com/konflux-ci/build-definitions/blob/main/task/buildah-oci-ta/0.2/buildah-oci-ta.yaml.


## buildah-oci-ta

It should be base-lined to the most recent definition from Konflux build-definitions repository and then the following changes should be applied to that:

### Indy Sidecar
Adds Indy configuration to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L134-L137
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L170-L180
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L737-L757


### Trusted CA
Adds the trusted ca to the build:
* https://github.com/redhat-appstudio/jvm-build-service/blob/main/deploy/tasks/buildah-oci-ta.yaml#L504-L508


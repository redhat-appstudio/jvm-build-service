# JVM Build Service CI documentation

Currently in jvm-build-service all tests are running in [Openshift CI](https://prow.ci.openshift.org/?job=*jvm-build-service*).

## Openshift CI

Openshift CI is a Kubernetes based CI/CD system. Jobs can be triggered by various types of events and report their status to many different services. In addition to job execution, Openshift CI provides GitHub automation in a form of policy enforcement, chat-ops via /foo style commands and automatic PR merging.

A documentation around onboarding components in Openshift CI can be found in the Openshift CI jobs [repository](https://github.com/openshift/release). All jvm-build-service jobs configurations are defined in https://github.com/openshift/release/tree/master/ci-operator/config/redhat-appstudio/jvm-build-service.

- `jvm-build-service-e2e`: Run build suite from [e2e-tests](https://github.com/redhat-appstudio/e2e-tests/tree/main/tests/build) repository.

The test container to run the e2e tests in Openshift Ci is built from: [this Dockerfile](https://github.com/redhat-appstudio/jvm-build-service/blob/main/.ci/openshift-ci/Dockerfile)

The following environments are used to launch the CI tests in Openshift CI:

| Variable | Required | Explanation | Default Value |
|---|---|---|---|
| `JVM_BUILD_SERVICE_IMAGE` | no | A valid jvm build service operator/controller container image reference from openshift CI. | `quay.io/redhat-appstudio/hacbs-jvm-controller@<SHA reference in infra-deployments>` |
| `JVM_BUILD_SERVICE_IMAGE_REPO` | no | A valid jvm build service operator/controller container without tag. | `quay.io/redhat-appstudio/hacbs-jvm-controller` |
| `JVM_BUILD_SERVICE_IMAGE_TAG` | no | A jvm valid build service operator/controller container tag. | `next` |
| `JVM_BUILD_SERVICE_CACHE_IMAGE` | no | A valid jvm build service cache container image reference from openshift CI. | `quay.io/redhat-appstudio/hacbs-jvm-cache@<SHA reference in infra-deployments>` |
| `JVM_BUILD_SERVICE_CACHE_IMAGE_REPO` | no | A valid jvm build service cache container without tag. | `quay.io/redhat-appstudio/hacbs-jvm-cache` |
| `JVM_BUILD_SERVICE_CACHE_IMAGE_TAG` | no | A jvm valid build service cache container tag. | `next` |
| `JVM_BUILD_SERVICE_SIDECAR_IMAGE` | no | A valid jvm build service sidecar container image reference from openshift CI. | `quay.io/redhat-appstudio/hacbs-jvm-sidecar@<SHA reference in infra-deployments>` |
| `JVM_BUILD_SERVICE_SIDECAR_IMAGE_REPO` | no | A valid jvm build service sidecar container without tag. | `quay.io/redhat-appstudio/hacbs-jvm-sidecar` |
| `JVM_BUILD_SERVICE_SIDECAR_IMAGE_TAG` | no | A jvm valid build service sidecar container tag. | `next` |
| `JVM_BUILD_SERVICE_ANALYZER_IMAGE` | no | A valid jvm build service dependency analyzer container image reference from openshift CI. | `quay.io/redhat-appstudio/hacbs-jvm-dependency-analyser@<SHA reference in infra-deployments>` |
| `JVM_BUILD_SERVICE_ANALYZER_IMAGE_REPO` | no | A valid jvm build service dependency analyzer container without tag. | `quay.io/redhat-appstudio/hacbs-jvm-dependency-analyser` |
| `JVM_BUILD_SERVICE_ANALYZER_IMAGE_TAG` | no | A jvm valid build service dependency analyzer container tag. | `next` |
| `JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE` | no | A valid jvm build service request processor container image reference from openshift CI. | `quay.io/redhat-appstudio/hacbs-jvm-request-processor@<SHA reference in infra-deployments>` |
| `JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE_REPO` | no | A valid jvm build service request processor container without tag. | `quay.io/redhat-appstudio/hacbs-jvm-request-processor` |
| `JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE_TAG` | no | A jvm valid build service request processor container tag. | `next` |
| `GITHUB_TOKEN` | yes | A github token used to create AppStudio applications in GITHUB  | ''  |
| `QUAY_TOKEN` | yes | A quay token to push components images to quay.io | '' |

#!/bin/bash
# exit immediately when a command fails
set -e
# only exit with zero if all commands of the pipeline exit successfully
set -o pipefail
# error on unset variables
set -u

command -v kubectl >/dev/null 2>&1 || { echo "kubectl is not installed. Aborting."; exit 1; }

export WORKSPACE JVM_BUILD_SERVICE_PR_OWNER JVM_BUILD_SERVICE_PR_SHA

WORKSPACE=$(dirname "$(dirname "$(readlink -f "$0")")");
export TEST_SUITE="jvm-build-service-suite"
export APPLICATION_NAMESPACE="openshift-gitops"
export APPLICATION_NAME="all-components-staging"

# JVM_BUILD_SERVICE_IMAGE - controller image built in openshift CI job workflow.
# JVM_BUILD_SERVICE_CACHE_IMAGE - cache image built in openshift CI job workflow.
# JVM_BUILD_SERVICE_SIDECAR_IMAGE - sidecar image built in openshift CI job workflow.
# JVM_BUILD_SERVICE_ANALYZER_IMAGE - dependency analyzer image built in openshift CI workflow.
# JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE - request processor image built in openshift CI workflow.

# More info about how image dependencies work in ci: https://github.com/openshift/ci-tools/blob/master/TEMPLATES.md#parameters-available-to-templates
# Container env defined at: https://github.com/openshift/release/blob/master/ci-operator/config/redhat-appstudio/jvm-build-service/redhat-appstudio-jvm-build-service-main.yaml
# Openshift CI generates the build service container image value with values like registry.build01.ci.openshift.org/ci-op-83gwcnmk/pipeline@sha256:8812e26b50b262d0cc45da7912970a205add4bd4e4ff3fed421baf3120027206. Need to get the image without sha.
# the repository like 'ci-op-83gwcnmk' corresponds to they dynamically generated namespace OpenShift CI creates for a particular PR's set of jobs.  That name will always start
# with 'ci-op-" but the last part will change per PR.  The image 'pipeline' refers to the 'pipeline' OpenShift ImageStream, and the SHA is the specific instance of the Image
# corresponding to an OpenShift ImageStreamTag, where likely, that SHA will change image build to image build.  However, we can also specify the ImageStreamTag name supplied
# in https://github.com/openshift/release/blob/master/ci-operator/config/redhat-appstudio/jvm-build-service/redhat-appstudio-jvm-build-service-main.yaml in place of the SHA
# and OpenShift ImageStream support will fetch the correct message (i.e. it maps to a docker image tag with direct image registry references)

# for ease of use we strip the SHA, via eliminating everything starting with the '@' and whatever follows, and use the constant ImageStreamTag name instead to fetch the correct image
export JVM_BUILD_SERVICE_IMAGE=${JVM_BUILD_SERVICE_IMAGE%@*}
export JVM_BUILD_SERVICE_IMAGE_REPO=${JVM_BUILD_SERVICE_IMAGE:-"quay.io/redhat-appstudio/hacbs-jvm-controller"}
# Tag, i.e. ImageStreamTag, defined at: https://github.com/openshift/release/blob/master/ci-operator/config/redhat-appstudio/jvm-build-service/redhat-appstudio-jvm-build-service-main.yaml
# as the default always reconciles in CI.  Override this for local testing.
export JVM_BUILD_SERVICE_IMAGE_TAG=${JVM_BUILD_SERVICE_IMAGE_TAG:-"redhat-appstudio-jvm-build-service-image"}

export JVM_BUILD_SERVICE_CACHE_IMAGE=${JVM_BUILD_SERVICE_CACHE_IMAGE%@*}
export JVM_BUILD_SERVICE_CACHE_IMAGE_REPO=${JVM_BUILD_SERVICE_CACHE_IMAGE:-"quay.io/redhat-appstudio/hacbs-jvm-cache"}
export JVM_BUILD_SERVICE_CACHE_IMAGE_TAG=${JVM_BUILD_SERVICE_CACHE_IMAGE_TAG:-"redhat-appstudio-jvm-build-service-cache-image"}

export JVM_BUILD_SERVICE_SIDECAR_IMAGE=${JVM_BUILD_SERVICE_SIDECAR_IMAGE%@*}
export JVM_BUILD_SERVICE_SIDECAR_IMAGE_REPO=${JVM_BUILD_SERVICE_SIDECAR_IMAGE:-"quay.io/redhat-appstudio/hacbs-jvm-sidecar"}
export JVM_BUILD_SERVICE_SIDECAR_IMAGE_TAG=${JVM_BUILD_SERVICE_SIDECAR_IMAGE_TAG:-"redhat-appstudio-jvm-build-service-sidecar-image"}

export JVM_BUILD_SERVICE_ANALYZER_IMAGE=${JVM_BUILD_SERVICE_ANALYZER_IMAGE%@*}
export JVM_BUILD_SERVICE_ANALYZER_IMAGE_REPO=${JVM_BUILD_SERVICE_ANALYZER_IMAGE:-"quay.io/redhat-appstudio/hacbs-jvm-dependency-analyser"}
export JVM_BUILD_SERVICE_ANALYZER_IMAGE_TAG=${JVM_BUILD_SERVICE_ANALYZER_IMAGE_TAG:-"redhat-appstudio-jvm-build-service-analyzer-image"}

export JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE=${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE%@*}
export JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE_REPO=${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE:-"quay.io/redhat-appstudio/hacbs-jvm-build-request-processor"}
export JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE_TAG=${JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE_TAG:-"redhat-appstudio-jvm-build-service-reqprocessor-image"}

if [[ -n "${JOB_SPEC}" && "${REPO_NAME}" == "jvm-build-service" ]]; then
    # Extract PR author and commit SHA to also override default kustomization in infra-deployments repo
    # https://github.com/redhat-appstudio/infra-deployments/blob/d3b56adc1bd2a7cf500793a7863660ea5117c531/hack/preview.sh#L88
    JVM_BUILD_SERVICE_PR_OWNER=$(jq -r '.refs.pulls[0].author' <<< "$JOB_SPEC")
    JVM_BUILD_SERVICE_PR_SHA=$(jq -r '.refs.pulls[0].sha' <<< "$JOB_SPEC")
fi

echo "GGM JOB_SPEC ${JOB_SPEC}"
echo "GGM REPO_NAME ${REPO_NAME}"
echo "GGM JVM_BUILD_SERVICE_PR_OWNER ${JVM_BUILD_SERVICE_PR_OWNER}"
echo "GGM JVM_BUILD_SERVICE_PR_SHA ${JVM_BUILD_SERVICE_PR_SHA}"

# Available openshift ci environments https://docs.ci.openshift.org/docs/architecture/step-registry/#available-environment-variables
export ARTIFACT_DIR=${ARTIFACT_DIR:-"/tmp/appstudio"}

function waitHASApplicationToBeReady() {
    while [ "$(kubectl get applications.argoproj.io has -n openshift-gitops -o jsonpath='{.status.health.status}')" != "Healthy" ]; do
        echo "[INFO] Waiting for HAS to be ready."
        sleep 30s
    done
}

function waitAppStudioToBeReady() {
    while [ "$(kubectl get applications.argoproj.io ${APPLICATION_NAME} -n ${APPLICATION_NAMESPACE} -o jsonpath='{.status.health.status}')" != "Healthy" ] ||
          [ "$(kubectl get applications.argoproj.io ${APPLICATION_NAME} -n ${APPLICATION_NAMESPACE} -o jsonpath='{.status.sync.status}')" != "Synced" ]; do
        echo "[INFO] Waiting for AppStudio to be ready."
        sleep 1m
    done
}

function waitBuildToBeReady() {
    while [ "$(kubectl get applications.argoproj.io build -n ${APPLICATION_NAMESPACE} -o jsonpath='{.status.health.status}')" != "Healthy" ] ||
          [ "$(kubectl get applications.argoproj.io build -n ${APPLICATION_NAMESPACE} -o jsonpath='{.status.sync.status}')" != "Synced" ]; do
        echo "[INFO] Waiting for Build to be ready."
        sleep 1m
    done
}

function executeE2ETests() {
    # E2E instructions can be found: https://github.com/redhat-appstudio/e2e-tests
    # The e2e binary is included in Openshift CI test container from the dockerfile: https://github.com/redhat-appstudio/infra-deployments/blob/main/.ci/openshift-ci/Dockerfile
    curl https://raw.githubusercontent.com/redhat-appstudio/e2e-tests/main/scripts/e2e-openshift-ci.sh | bash -s

    cd ${WORKSPACE}/tmp/e2e-tests
    ./bin/e2e-appstudio --ginkgo.junit-report="${ARTIFACT_DIR}"/e2e-report.xml --ginkgo.focus="${TEST_SUITE}" --ginkgo.progress --ginkgo.v --ginkgo.no-color
}

curl https://raw.githubusercontent.com/redhat-appstudio/e2e-tests/main/scripts/install-appstudio-e2e-mode.sh | bash -s install

export -f waitAppStudioToBeReady
export -f waitBuildToBeReady
export -f waitHASApplicationToBeReady

# Install AppStudio Controllers and wait for HAS and other AppStudio application to be running.
timeout --foreground 10m bash -c waitAppStudioToBeReady
timeout --foreground 10m bash -c waitBuildToBeReady
timeout --foreground 10m bash -c waitHASApplicationToBeReady

executeE2ETests

#!/bin/bash
# exit immediately when a command fails
set -e
# only exit with zero if all commands of the pipeline exit successfully
set -o pipefail
# error on unset variables
set -u

source $(dirname $0)/common-ci-settings-and-functions.sh

function executeE2ETests() {
    # E2E instructions can be found: https://github.com/redhat-appstudio/e2e-tests
    # The e2e binary is included in Openshift CI test container from the dockerfile: https://github.com/redhat-appstudio/infra-deployments/blob/main/.ci/openshift-ci/Dockerfile
    curl https://raw.githubusercontent.com/redhat-appstudio/e2e-tests/main/scripts/e2e-openshift-ci.sh | bash -s

    cd ${WORKSPACE}/tmp/e2e-tests
    ./bin/e2e-appstudio --ginkgo.junit-report="${ARTIFACT_DIR}"/e2e-report.xml --ginkgo.focus="${TEST_SUITE}" --ginkgo.progress --ginkgo.v --ginkgo.no-color
}

executeE2ETests

#!/bin/bash
# This script will generate a new Tekton bundle that allows the overriding of images that are hard-coded into task steps
# It minimally requires the JVM_BUILD_SERVICE_PR_SHA env var be set.  In the case of non PR openshift CI tests like
# rehearsal jobs in openshift/release or periodics, we do not need to override the bundle and we just use the latest
# at quay.io/redhat-appstudio
PR_RUN=${JVM_BUILD_SERVICE_PR_SHA:-notpr}
if [ "$PR_RUN" == "notpr" ]; then
  echo "JVM_BUILD_SERVICE_PR_SHA is not set so aborting"
  exit 0
fi
TEMP_FOLDER=$WORKSPACE/tmp/bundle-override
APPSTUDIO_QE_REPO=quay.io/redhat-appstudio-qe/test-images
TASK_BUNDLE_IMG=$APPSTUDIO_QE_REPO:task-bundle-$JVM_BUILD_SERVICE_PR_SHA
PIPELINE_BUNDLE_IMG=$APPSTUDIO_QE_REPO:pipeline-bundle-$JVM_BUILD_SERVICE_PR_SHA

function getCurrentBuildBundle() {
    curl -s -o "$TEMP_FOLDER"/build-kustomization.yaml https://raw.githubusercontent.com/redhat-appstudio/infra-deployments/main/components/build/kustomization.yaml

    BUILD_BUNDLE=$(yq e ".configMapGenerator[].literals[]" \
    "$TEMP_FOLDER"/build-kustomization.yaml \
    | grep default_build_bundle \
    | sed "s/default_build_bundle=//")

    echo "New build bundle will be created based on $BUILD_BUNDLE"
}

function createPipelinesFile() {
    pipelines=$(tkn bundle list "$BUILD_BUNDLE" | sed "s/pipeline.tekton.dev\///")
    for pipeline in $pipelines; do
        echo "---" >> "$TEMP_FOLDER"/pipelines.yaml
        tkn bundle list -o yaml "$BUILD_BUNDLE" pipeline "$pipeline" >> "$TEMP_FOLDER"/pipelines.yaml
    done
}

function createTaskFile() {
    for bundle in $(grep bundle "$TEMP_FOLDER"/pipelines.yaml | sort -u | awk '{print $2}'); do 
        tasks=$(tkn bundle list "$bundle" | sed "s/task.tekton.dev\///")

        for task in $tasks; do
            if [ "$task" == "s2i-java" ]; then
                tkn bundle list -o yaml "$bundle" task "$task" >> "$TEMP_FOLDER"/task.yaml
            fi
        done
    done
}

function updateAnalyzerImage() {
    echo "jvm-build-service analyzer image set to $JVM_BUILD_SERVICE_ANALYZER_IMAGE"
    yq e -i "select(.metadata.name == \"s2i-java\") \
    | (.spec.steps[] | select(.name == \"analyse-dependencies-java-sbom\").image) \
    |= \"$JVM_BUILD_SERVICE_ANALYZER_IMAGE\"" "$TEMP_FOLDER"/task.yaml
}

function updatePipelineRef() {
    yq e -i "(.spec.tasks[].taskRef | select (.name == \"s2i-java\").bundle) \
    |= \"$TASK_BUNDLE_IMG\"" "$TEMP_FOLDER"/pipelines.yaml
}

function createDockerConfig() {
    mkdir ~/.docker
    echo "$QUAY_TOKEN" | base64 -d > ~/.docker/config.json
}

function createAndPushTaskBundle() {
    echo "Creating $TASK_BUNDLE_IMG"
    tkn bundle push "$TASK_BUNDLE_IMG" -f "$TEMP_FOLDER"/task.yaml
}

function createAndPushPipelineBundle() {
    echo "Creating $PIPELINE_BUNDLE_IMG"
    tkn bundle push "$PIPELINE_BUNDLE_IMG" -f "$TEMP_FOLDER"/pipelines.yaml
}

mkdir -p "$TEMP_FOLDER"

getCurrentBuildBundle
createPipelinesFile
createTaskFile
updateAnalyzerImage
updatePipelineRef
createDockerConfig
createAndPushTaskBundle
createAndPushPipelineBundle

export DEFAULT_BUILD_BUNDLE="$PIPELINE_BUNDLE_IMG"

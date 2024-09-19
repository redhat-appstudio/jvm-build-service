#!/bin/bash

DIR="$( cd "$( dirname "$0" )" && pwd )"

if [ -z "$QUAY_USERNAME" ]; then
    echo "QUAY_USERNAME should be set"
    exit 1
fi
if [ -z "$JBS_BUILD_IMAGE_SECRET" ]; then
    echo "JBS_BUILD_IMAGE_SECRET should be set"
    exit 1
fi
if [ -z "$JBS_WORKER_NAMESPACE" ]; then
    export JBS_WORKER_NAMESPACE=test-jvm-namespace
fi
if [ -z "$MAVEN_USERNAME" ]; then
    export MAVEN_USERNAME=admin
fi
if [ -z "$MAVEN_PASSWORD" ]; then
    export MAVEN_PASSWORD=secret
fi
if [ -z "$MAVEN_REPOSITORY" ]; then
    export MAVEN_REPOSITORY='http://jvm-build-maven-repo.$(context.taskRun.namespace).svc.cluster.local/releases'
fi

if [ -n "$DBTESTSET" ]; then
    echo "DBTESTSET is set. DependencyBuild test set will be run"
elif [ -n "$ABTESTSET" ]; then
    echo "ABTESTSET is set. ArtifactBuild test set will be run"
else
    echo "ABTESTSET or DBTESTSET should be set, unless wanting to run sample pipeline"
fi

show_menu() {
    echo "============================="
    echo "   Minikube Local Development"
    echo "============================="
    echo "1. Build & push dev images, start & deploy Minikube, run tests"
    echo "2. Start & deploy Minikube, run tests"
    echo "3. Start Minikube, run tests"
    echo "4. Run tests"
    echo "5. Stop and clean Minikube"
    echo "6. Exit"
    echo "============================="
    read -p "Please select an option (1-6): " choice
    return $choice
}

build_and_push_images() {
    echo "Build & push dev images:"
    cd $DIR/..
    make dev
    cd $DIR
}

start_minikube() {
    echo "Start Minikube:"
    minikube start --memory=max --cpus=max
}

deploy_minikube() {
    echo "Deploy Minikube:"
    $DIR/minikube-ci.sh
}

clean_minikube() {
    echo "Clean Minikube:"
    minikube delete
}

stop_minikube() {
    echo "Stop Minikube:"
    minikube stop
}

run_tests() {
    echo "Run tests:"
    cd $DIR/..
    make minikube-test
    cd $DIR
}

handle_choice() {
    case $1 in
        1)
            build_and_push_images
            clean_minikube
            start_minikube
            deploy_minikube
            run_tests
            ;;
        2)
            clean_minikube
            start_minikube
            deploy_minikube
            run_tests
            ;;
        3)
            start_minikube
            run_tests
            ;;
        4)
            run_tests
            ;;
        5)
            stop_minikube
            clean_minikube
            ;;
        6)
            echo "Exiting"
            exit 0
            ;;
        *)
            echo "Invalid choice. Please select a number between 1 and 6"
            ;;
    esac
}

while true; do
    show_menu
    choice=$?
    handle_choice $choice
done

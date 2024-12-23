SHELL := /bin/bash

# Get the currently used golang install path (in GOPATH/bin, unless GOBIN is set)
ifeq (,$(shell go env GOBIN))
GOBIN=$(shell go env GOPATH)/bin
else
GOBIN=$(shell go env GOBIN)
endif

# options for generating crds with controller-gen
CONTROLLER_GEN=${GOBIN}/controller-gen
CRD_OPTIONS ?= crd

.EXPORT_ALL_VARIABLES:

default: build

fmt: ## Run go fmt against code.
	go fmt ./cmd/... ./pkg/...

vet: ## Run go vet against code.
	go vet ./cmd/... ./pkg/...

test: fmt vet ## Run tests.
	go test -v ./pkg/... -coverprofile cover.out

openshift-e2e:
	KUBERNETES_CONFIG=${KUBECONFIG} go test -count 1 -tags normal -timeout 120m -v ./openshift-with-appstudio-test/...

openshift-e2e-periodic:
	KUBERNETES_CONFIG=${KUBECONFIG} go test -count 1 -tags periodic -timeout 180m -v ./openshift-with-appstudio-test/...

openshift-e2e-quickstarts:
	KUBERNETES_CONFIG=${KUBECONFIG} go test -count 1 -tags quickstarts -timeout 180m -v ./openshift-with-appstudio-test/...

openshift-e2e-jbs:
	KUBERNETES_CONFIG=${KUBECONFIG} go test -count 1 -tags jbs -timeout 180m -v ./openshift-with-appstudio-test/...

openshift-e2e-wildfly:
	KUBERNETES_CONFIG=${KUBECONFIG} go test -count 1 -tags wildfly -timeout 180m -v ./openshift-with-appstudio-test/...

minikube-test:
	go test -count 1 -tags minikube -timeout 180m -v ./openshift-with-appstudio-test/e2e
build:
	go build -o out/jvmbuildservice cmd/controller/main.go
	env GOOS=linux GOARCH=amd64 GOTOOLCHAIN=auto GOSUMDB=sum.golang.org go build -mod=vendor -o out/jvmbuildservice ./cmd/controller

clean:
	rm -rf out

generate-crds:
	hack/install-controller-gen.sh
	"$(CONTROLLER_GEN)" "$(CRD_OPTIONS)" object:headerFile="hack/boilerplate.go.txt" rbac:roleName=manager-role webhook paths=./pkg/apis/jvmbuildservice/v1alpha1 output:crd:artifacts:config=deploy/crds/base
	 go install k8s.io/code-generator/cmd/client-gen
	client-gen --go-header-file "hack/boilerplate.go.txt" --input jvmbuildservice/v1alpha1 --input-base github.com/redhat-appstudio/jvm-build-service/pkg/apis --output-package github.com/redhat-appstudio/jvm-build-service/pkg/client/clientset --output-base ../../../ -v 100 --clientset-name versioned

generate: generate-crds
	cp deploy/crds/base/* java-components/resource-model/src/main/resources/crds

verify-generate-deepcopy-client: generate-deepcopy-client
	hack/verify-codegen.sh

dev-image:
	@if [ -z "$$QUAY_USERNAME" ]; then \
            echo "ERROR: QUAY_USERNAME is not set"; \
            exit 1; \
    fi
	docker build . -t quay.io/$(QUAY_USERNAME)/hacbs-jvm-controller:"$${JBS_QUAY_IMAGE_TAG:-dev}"
	docker push quay.io/$(QUAY_USERNAME)/hacbs-jvm-controller:"$${JBS_QUAY_IMAGE_TAG:-dev}"

dev: dev-image
	cd java-components && mvn clean install -Dlocal -DskipTests -Ddev

dev-openshift: dev
	./deploy/openshift-development.sh

dev-minikube: dev
	./deploy/minikube-development.sh

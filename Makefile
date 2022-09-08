SHELL := /bin/bash

# Get the currently used golang install path (in GOPATH/bin, unless GOBIN is set)
ifeq (,$(shell go env GOBIN))
GOBIN=$(shell go env GOPATH)/bin
else
GOBIN=$(shell go env GOBIN)
endif

# CONTROL_PLANE defines the type of cluster that will be used. Possible values are kubernetes (default) and kcp.
CONTROL_PLANE ?= kubernetes

# options for generating crds with controller-gen
CONTROLLER_GEN="${GOBIN}/controller-gen"
CRD_OPTIONS ?= "crd:trivialVersions=true,preserveUnknownFields=false"

.EXPORT_ALL_VARIABLES:

default: build

fmt: ## Run go fmt against code.
	go fmt ./cmd/... ./pkg/...

vet: ## Run go vet against code.
	go vet ./cmd/... ./pkg/...

test: fmt vet ## Run tests.
	go test -v ./pkg/... -coverprofile cover.out

e2etest: fmt vet envtest ## Run tests.
	KUBEBUILDER_ASSETS="$(shell $(ENVTEST) use $(ENVTEST_K8S_VERSION) -p path)" go test -count 1 -v ./test/...

appstudio-installed-on-openshift-e2e:
	KUBERNETES_CONFIG=${KUBECONFIG} go test -count 1 -tags normal -timeout 120m -v ./openshift-with-appstudio-test/...

appstudio-installed-on-openshift-periodic:
	KUBERNETES_CONFIG=${KUBECONFIG} go test -count 1 -tags periodic -timeout 180m -v ./openshift-with-appstudio-test/...

build:
	go build -o out/jvmbuildservice cmd/controller/main.go
	env GOOS=linux GOARCH=amd64 go build -mod=vendor -o out/jvmbuildservice ./cmd/controller

clean:
	rm -rf out

generate-deepcopy-client:
	hack/update-codegen.sh

generate-crds:
	hack/install-controller-gen.sh
	"$(CONTROLLER_GEN)" "$(CRD_OPTIONS)" rbac:roleName=manager-role webhook paths=./pkg/apis/jvmbuildservice/v1alpha1 output:crd:artifacts:config=deploy/crds/base
ifeq ($(CONTROL_PLANE), kcp)
	hack/generate-kcp-api.sh
endif

generate: generate-crds generate-deepcopy-client

verify-generate-deepcopy-client: generate-deepcopy-client
	hack/verify-codegen.sh

dev-image:
	docker build . -t quay.io/$(QUAY_USERNAME)/hacbs-jvm-controller:dev
	docker push quay.io/$(QUAY_USERNAME)/hacbs-jvm-controller:dev

dev: dev-image
	cd java-components && mvn clean install -Dlocal -DskipTests

dev-openshift: dev
	./deploy/openshift-development.sh


dev-minikube: dev
	./deploy/minikube-development.sh

ENVTEST = $(shell pwd)/bin/setup-envtest
envtest: ## Download envtest-setup locally if necessary.
	$(call go-get-tool,$(ENVTEST),sigs.k8s.io/controller-runtime/tools/setup-envtest@latest)

# go-get-tool will 'go get' any package $2 and install it to $1.
PROJECT_DIR := $(shell dirname $(abspath $(lastword $(MAKEFILE_LIST))))
define go-get-tool
@[ -f $(1) ] || { \
set -e ;\
TMP_DIR=$$(mktemp -d) ;\
cd $$TMP_DIR ;\
go mod init tmp ;\
echo "Downloading $(2)" ;\
GOBIN=$(PROJECT_DIR)/bin go get $(2) ;\
rm -rf $$TMP_DIR ;\
}
endef

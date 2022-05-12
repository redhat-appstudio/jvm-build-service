SHELL := /bin/bash

# Get the currently used golang install path (in GOPATH/bin, unless GOBIN is set)
ifeq (,$(shell go env GOBIN))
GOBIN=$(shell go env GOPATH)/bin
else
GOBIN=$(shell go env GOBIN)
endif

# options for generating crds with controller-gen
CONTROLLER_GEN="${GOBIN}/controller-gen"
CRD_OPTIONS ?= "crd:trivialVersions=true,preserveUnknownFields=false"

.EXPORT_ALL_VARIABLES:

default: build

build:
	#go build -o out/jvmbuildservice ./cmd/controller/main.go
	env GOOS=linux GOARCH=amd64 go build -mod=vendor -o out/jvmbuildservice ./cmd/controller

clean:
	rm -rf out

generate-deepcopy-client:
	hack/update-codegen.sh

generate-crds:
	hack/install-controller-gen.sh
	"$(CONTROLLER_GEN)" "$(CRD_OPTIONS)" rbac:roleName=manager-role webhook paths=./pkg/apis/jvmbuildservice/v1alpha1 output:crd:artifacts:config=deploy/crds/base

verify-generate-deepcopy-client: generate-deepcopy-client
	hack/verify-codegen.sh

dev-image:
	docker build . -t quay.io/$(QUAY_USERNAME)/hacbs-jvm-controller:dev
	docker push quay.io/$(QUAY_USERNAME)/hacbs-jvm-controller:dev


dev: dev-image
	cd java-components && mvn clean install -Dlocal
	./deploy/development.sh
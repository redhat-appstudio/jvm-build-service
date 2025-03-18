# Build the manager binary
FROM registry.access.redhat.com/ubi9/go-toolset:9.5-1742197705@sha256:564c50dbad93a50dfe1439b295f021dae0bdc8c2aef3ad8be7b2a4dde52f0e2f as builder

# Copy the Go Modules manifests
COPY go.mod go.mod
COPY vendor/ vendor/
COPY pkg/ pkg/
COPY cmd/ cmd/

# Build
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 GOTOOLCHAIN=auto GOSUMDB=sum.golang.org go build -o jvmbuildservice cmd/controller/main.go

# Use ubi-minimal as minimal base image to package the manager binary
# Refer to https://catalog.redhat.com/software/containers/ubi8/ubi-minimal/5c359a62bed8bd75a2c3fba8 for more details
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.5-1731604394@sha256:46f77b7dfba47b041de4c9d8516c24081fc92cc7743fca4a353e7f1c2a4beb19
COPY --from=builder /opt/app-root/src/jvmbuildservice /
USER 65532:65532

ENTRYPOINT ["/jvmbuildservice"]

# Build the manager binary
FROM registry.access.redhat.com/ubi9/go-toolset:1.21.9-1.1714671022@sha256:f04d515e747fbd9ef5e55a7d34808e4abf1d62623515d0e97c12b51cc0008de3 as builder

# Copy the Go Modules manifests
COPY go.mod go.mod
COPY vendor/ vendor/
COPY pkg/ pkg/
COPY cmd/ cmd/

# Build
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o jvmbuildservice cmd/controller/main.go

# Use ubi-minimal as minimal base image to package the manager binary
# Refer to https://catalog.redhat.com/software/containers/ubi8/ubi-minimal/5c359a62bed8bd75a2c3fba8 for more details
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.4-949@sha256:b6ec3ea97ba321c7529b81ae45c407ba8039d52fea3f7b6853734d7f8863344b
COPY --from=builder /opt/app-root/src/jvmbuildservice /
USER 65532:65532

ENTRYPOINT ["/jvmbuildservice"]

# Build the manager binary
FROM registry.access.redhat.com/ubi9/go-toolset:1.21.9-1.1717085562@sha256:d1911ff6e8b3d17175dafe00aa466e83c3ceec45903b01a7cc18e9e417781263 as builder

# Copy the Go Modules manifests
COPY go.mod go.mod
COPY vendor/ vendor/
COPY pkg/ pkg/
COPY cmd/ cmd/

# Build
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o jvmbuildservice cmd/controller/main.go

# Use ubi-minimal as minimal base image to package the manager binary
# Refer to https://catalog.redhat.com/software/containers/ubi8/ubi-minimal/5c359a62bed8bd75a2c3fba8 for more details
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.4-949.1717074713@sha256:0d6b09f233745d2fcf892cebcf1c18bbfed497f116bc8357e9db4b724d76c5a9
COPY --from=builder /opt/app-root/src/jvmbuildservice /
USER 65532:65532

ENTRYPOINT ["/jvmbuildservice"]

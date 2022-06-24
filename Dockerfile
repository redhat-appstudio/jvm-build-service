# Build the manager binary
FROM registry.access.redhat.com/ubi8/go-toolset:1.17.7-13.1655148239 as builder

WORKDIR /workspace
# Copy the Go Modules manifests
COPY go.mod go.mod
COPY vendor/ vendor/
COPY pkg/ pkg/
COPY cmd/ cmd/

# Build
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o jvmbuildservice cmd/controller/main.go
# Use distroless as minimal base image to package the manager binary
# Refer to https://github.com/GoogleContainerTools/distroless for more details
FROM registry.access.redhat.com/ubi8/ubi-minimal:8.6-751
WORKDIR /
COPY --from=builder /workspace/jvmbuildservice .
USER 65532:65532

ENTRYPOINT ["/jvmbuildservice"]
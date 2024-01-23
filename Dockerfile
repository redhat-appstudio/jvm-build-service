# Build the manager binary
FROM registry.access.redhat.com/ubi9/go-toolset:1.20.10-6@sha256:077f292da8bea9ce7f729489cdbd217dd268ce300f3e216cb1fffb38de7daeb9 as builder

# Copy the Go Modules manifests
COPY go.mod go.mod
COPY vendor/ vendor/
COPY pkg/ pkg/
COPY cmd/ cmd/

# Build
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o jvmbuildservice cmd/controller/main.go

# Use ubi-minimal as minimal base image to package the manager binary
# Refer to https://catalog.redhat.com/software/containers/ubi8/ubi-minimal/5c359a62bed8bd75a2c3fba8 for more details
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.2@sha256:c8c7a06ce1c5fa23c1cbd7a0fd891eacd099bc232aa9985ddb183cfe98d1deaf
COPY --from=builder /opt/app-root/src/jvmbuildservice /
USER 65532:65532

ENTRYPOINT ["/jvmbuildservice"]

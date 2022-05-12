# Build the manager binary
FROM golang:1.17 as builder
# TODO: find out from build service team why they are not using the openshift golang
#FROM registry.ci.openshift.org/ocp/builder:rhel-8-golang-1.17-openshift-4.11 AS builder
WORKDIR /go/src/github.com/redhat-appstudio/jvm-build-service
COPY . .
RUN make build

# Use distroless as minimal base image to package the manager binary
# Refer to https://github.com/GoogleContainerTools/distroless for more details
FROM gcr.io/distroless/static:nonroot
# TODO find out from build service team why they are not using base ocp for controllers
#FROM registry.ci.openshift.org/ocp/4.11:base
COPY --from=builder /go/src/github.com/redhat-appstudio/jvm-build-service/out/jvmbuildservice /usr/bin
USER 65532:65532
ENTRYPOINT ["/usr/bin/jvmbuildservice"]
# TODO potentially visit CMD vs. ENTRYPOINT decision with build service team
#CMD ["/usr/bin/jvmbuildservice"]
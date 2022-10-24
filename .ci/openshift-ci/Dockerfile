FROM registry.ci.openshift.org/openshift/release:golang-1.18

SHELL ["/bin/bash", "-c"]

#TODO installing maven and java 17 from registry.ci.openshift.org/openshift/release:golang-1.17 was very problematic
# most likely we'll need an additional Dockerfile that is referenced from openshift/release for when we want to start
# building the java based images and tagging them for testing

# Install yq, kubectl, kustomize
RUN wget https://github.com/mikefarah/yq/releases/download/v4.25.1/yq_linux_amd64 -O /usr/local/bin/yq && \
    chmod +x /usr/local/bin/yq && yq --version && \
    curl -LO https://storage.googleapis.com/kubernetes-release/release/$(curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt)/bin/linux/amd64/kubectl && \
    chmod +x ./kubectl && \
    mv ./kubectl /usr/local/bin && \
    curl -LO https://github.com/tektoncd/cli/releases/download/v0.24.0/tkn_0.24.0_Linux_x86_64.tar.gz && \
    tar xvzf tkn_0.24.0_Linux_x86_64.tar.gz --no-same-owner -C /usr/local/bin/ tkn

FROM quay.io/redhat-appstudio/buildah:v1.35.4@sha256:3d3575bb7d0df64abcf1f22f06e82101a945d03317db1f3caac12814f796d01c
RUN dnf install -y iproute
COPY client/target/domainproxy-client-999-SNAPSHOT-runner /app/domainproxy-client-runner
COPY server/target/domainproxy-server-999-SNAPSHOT-runner /app/domainproxy-server-runner

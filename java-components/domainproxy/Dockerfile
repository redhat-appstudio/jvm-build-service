FROM quay.io/redhat-appstudio/buildah:v1.31.0@sha256:34f12c7b72ec2c28f1ded0c494b428df4791c909f1f174dd21b8ed6a57cf5ddb
RUN dnf install -y iproute
COPY client/target/domainproxy-client-1.0.0-SNAPSHOT-runner /app/domainproxy-client-runner
COPY server/target/domainproxy-server-1.0.0-SNAPSHOT-runner /app/domainproxy-server-runner
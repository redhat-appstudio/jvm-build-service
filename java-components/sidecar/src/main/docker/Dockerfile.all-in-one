FROM registry.access.redhat.com/ubi8/openjdk-17:1.13 AS builder

WORKDIR /work
COPY ./ .

RUN mvn -B package -pl sidecar -am -Dmaven.test.skip

FROM registry.access.redhat.com/ubi8/openjdk-17-runtime:1.13
WORKDIR /work/

COPY --from=builder /work/sidecar/target/quarkus-app/lib/ /deployments/lib/
COPY --from=builder /work/sidecar/target/quarkus-app/*.jar /deployments/
COPY --from=builder /work/sidecar/target/quarkus-app/app/ /deployments/app/
COPY --from=builder /work/sidecar/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 1001

ENV AB_JOLOKIA_OFF=""
ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

# domainproxy

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: https://quarkus.io/ .

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell script
mvn compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

## Packaging and running the application

The application can be packaged using:
```shell script
mvn package
```
It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directories for both server and client.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the corresponding `target/quarkus-app/lib/` directories.

The application is now runnable using:

`java -jar server/target/quarkus-app/quarkus-run.jar`

`java -jar client/target/quarkus-app/quarkus-run.jar`

If you want to build an _über-jar_, execute the following command:
```shell script
mvn package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using:

`java -jar server/target/domainproxy-server-999-SNAPSHOT-runner.jar`

`java -jar client/target/domainproxy-client-999-SNAPSHOT-runner.jar`

## Creating a native executable

You can create a native executable using:
```shell script
mvn package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:
```shell script
mvn package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executables with:

`./server/target/domainproxy-server-999-SNAPSHOT-runner`

`./client/target/domainproxy-client-999-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/maven-tooling.

## Building and pushing Docker image

`docker build . -t quay.io/<repository>/domain-proxy`

`docker login quay.io`

`docker push quay.io/<repository>/domain-proxy:latest`

## Related Guides

- REST ([guide](https://quarkus.io/guides/rest)): A Jakarta REST implementation utilizing build time processing and Vert.x. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it.
- REST Client ([guide](https://quarkus.io/guides/rest-client)): Call REST services

## Provided Code

### REST Client

Invoke different services through REST with JSON

[Related guide section...](https://quarkus.io/guides/rest-client)

### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)

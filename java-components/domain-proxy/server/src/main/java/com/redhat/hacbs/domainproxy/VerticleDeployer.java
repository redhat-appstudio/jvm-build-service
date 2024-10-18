package com.redhat.hacbs.domainproxy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.core.Vertx;

@ApplicationScoped
public class VerticleDeployer {

    public void init(final @Observes StartupEvent e, final Vertx vertx, final ExternalProxyVerticle verticle) {
        vertx.deployVerticle(verticle).await().indefinitely();
    }
}

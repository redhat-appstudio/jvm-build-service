package com.redhat.hacbs.artifactcache.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;

import io.quarkus.arc.Arc;
import io.quarkus.arc.Unremovable;
import io.quarkus.logging.Log;

/**
 * A utility to ensure resources are correctly cleaned up at the end of the request.
 * <p>
 * This is useful as some HTTP client resources have lifecycles that are not super well defined so
 * a try/finally block cannot be used. This ensures that these resources are correctly cleaned up.
 */
@RequestScoped
@Unremovable
public class RequestCleanup {

    final List<Closeable> resources = new ArrayList<>();

    public void addResource(Closeable c) {
        resources.add(c);
    }

    @PreDestroy
    void close() {
        for (var i : resources) {
            try {
                i.close();
            } catch (IOException e) {
                Log.error("Failed to close resource", e);
            }
        }
    }

    public static RequestCleanup instance() {
        return Arc.container().instance(RequestCleanup.class).get();
    }
}

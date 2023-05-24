package io.github.redhatappstudio.jvmbuild.cli;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import io.quarkus.arc.Unremovable;

public abstract class RequestScopedCompleter implements Iterable<String> {

    protected abstract Iterable<String> completionCandidates(KubernetesClient client);

    @Override
    public final Iterator<String> iterator() {
        return Cache.wrap(this);
    }

    @RequestScoped
    @Unremovable
    public static class Cache {

        @Inject
        KubernetesClient client;

        final Map<Class<? extends RequestScopedCompleter>, Iterable<String>> cache = new HashMap<>();

        public static <T extends RequestScopedCompleter> Iterator<String> wrap(T instance) {
            return Arc.container().instance(Cache.class).get().wrapInternal(instance);
        }

        public <T extends RequestScopedCompleter> Iterator<String> wrapInternal(T instance) {
            return cache.computeIfAbsent(instance.getClass(), s -> instance.completionCandidates(client)).iterator();
        }
    }

}

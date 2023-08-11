package io.github.redhatappstudio.jvmbuild.cli;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class TestResourceManager
        implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        return Map.of("store.rebuilt.type", "oci_registry", "store.rebuilt.registry", "dummy-registry",
                "store.rebuilt.owner", "none", "store.rebuilt.insecure", "true");
    }

    @Override
    public void stop() {

    }
}

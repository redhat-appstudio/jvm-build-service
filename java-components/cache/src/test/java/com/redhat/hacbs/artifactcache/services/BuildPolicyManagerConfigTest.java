package com.redhat.hacbs.artifactcache.services;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

@QuarkusTest
public class BuildPolicyManagerConfigTest {

    @Test
    public void testAllRepositoriesMissingIsError() {
        SmallRyeConfig config = new SmallRyeConfigBuilder().build();
        BuildPolicyManager manager = new BuildPolicyManager();
        Assertions.assertThrows(IllegalStateException.class, () -> {
            manager.createBuildPolicies(Set.of("default"), config);
        });
    }

    @Test
    public void testCentralConfiguredRedhatMissing() throws Exception {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(
                        Map.of("store.central.url", "https://repo.maven.apache.org/maven2", "build-policy.default.store-list",
                                "central,redhat"),
                        "test", 1))
                .build();
        BuildPolicyManager manager = new BuildPolicyManager();
        var policies = manager.createBuildPolicies(Set.of("default", "central-only"), config);
        var result = policies.get("default").getRepositories();
        Assertions.assertEquals(1, result.size());
        Repository central = result.get(0);
        Assertions.assertEquals("https://repo.maven.apache.org/maven2", central.getUri());
    }

    @Test
    public void testCentralAndRedHatConfigured() throws Exception {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(Map.of("store.central.url", "https://repo.maven.apache.org/maven2",
                        "store.redhat.url", "https://maven.repository.redhat.com/ga", "build-policy.default.store-list",
                        "central,redhat"), "test", 1))
                .build();
        BuildPolicyManager manager = new BuildPolicyManager();
        var policies = manager.createBuildPolicies(Set.of("default", "central-only"), config);
        var result = policies.get("default").getRepositories();
        Repository central = result.get(0);
        Assertions.assertEquals("https://repo.maven.apache.org/maven2", central.getUri());
        Repository rht = result.get(1);
        Assertions.assertEquals("https://maven.repository.redhat.com/ga", rht.getUri());
    }
}

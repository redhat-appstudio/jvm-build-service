package com.redhat.hacbs.artifactcache.services;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

@QuarkusTest
public class RepositoryManagerConfigTest {

    @Test
    public void testAllRepositoriesMissingIsError() {
        SmallRyeConfig config = new SmallRyeConfigBuilder().build();
        RepositoryManager manager = new RepositoryManager();
        Assertions.assertThrows(IllegalStateException.class, () -> {
            manager.createRepositoryInfo(List.of("central", "redhat"), config);
        });
    }

    @Test
    public void testCentralConfiguredRedhatMissing() throws Exception {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(
                        Map.of("repository.central.url", "https://repo.maven.apache.org/maven2"), "test", 1))
                .build();
        RepositoryManager manager = new RepositoryManager();
        var result = manager.createRepositoryInfo(List.of("central", "redhat"), config);
        Assertions.assertEquals(1, result.size());
        Repository central = result.get(0);
        Assertions.assertEquals(new URI("https://repo.maven.apache.org/maven2"), central.getUri());
    }

    @Test
    public void testCentralAndRedHatConfigured() throws Exception {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(Map.of("repository.central.url", "https://repo.maven.apache.org/maven2",
                        "repository.redhat.url", "https://maven.repository.redhat.com/ga"), "test", 1))
                .build();
        RepositoryManager manager = new RepositoryManager();
        var result = manager.createRepositoryInfo(List.of("central", "redhat"), config);
        Assertions.assertEquals(2, result.size());
        Repository central = result.get(0);
        Assertions.assertEquals(new URI("https://repo.maven.apache.org/maven2"), central.getUri());
        Repository rht = result.get(1);
        Assertions.assertEquals(new URI("https://maven.repository.redhat.com/ga"), rht.getUri());
    }
}

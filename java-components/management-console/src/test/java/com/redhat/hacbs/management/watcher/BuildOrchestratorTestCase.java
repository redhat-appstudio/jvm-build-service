package com.redhat.hacbs.management.watcher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.management.model.BuildIdentifier;
import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.ScmRepository;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;

@WithKubernetesTestServer
@QuarkusTest
public class BuildOrchestratorTestCase {
    @Inject
    KubernetesClient client;

    @Inject
    BuildOrchestrator buildOrchestrator;

    @AfterEach
    public void clean() {
        client.resources(ArtifactBuild.class).delete();
        client.resources(DependencyBuild.class).delete();
    }

    @Test
    @TestTransaction
    public void testBuildOrchestrator() {

        Assertions.assertTrue(client.resources(ArtifactBuild.class).list().getItems().isEmpty());
        BuildQueue bq = new BuildQueue();
        bq.mavenArtifact = MavenArtifact.forGav("com.foo:test:1.0");
        bq.priority = true;
        bq.persistAndFlush();
        buildOrchestrator.checkBuildQueue();
        Assertions.assertEquals(1, client.resources(ArtifactBuild.class).list().getItems().size());

        for (int i = 0; i < 10; ++i) {
            bq = new BuildQueue();
            bq.mavenArtifact = MavenArtifact.forGav("com.foo:test:" + i);
            bq.priority = true;
            bq.persistAndFlush();
            buildOrchestrator.checkBuildQueue();
        }
        Assertions.assertEquals(8, client.resources(ArtifactBuild.class).list().getItems().size());
    }

    @Test
    @TestTransaction
    public void testExistingBuilds() {

        ScmRepository scm = new ScmRepository();
        scm.url = "http://github.com/foo";
        scm.persistAndFlush();
        for (int i = 0; i < 5; ++i) {
            StoredDependencyBuild bd = new StoredDependencyBuild();
            bd.uid = UUID.randomUUID().toString();
            bd.creationTimestamp = Instant.now();
            bd.buildIdentifier = BuildIdentifier.findORCreate("http://github.com/foo", "foo" + i, "sdfsdajklfdskl", "",
                    "somename" + i);
            bd.producedArtifacts = new ArrayList<>();
            bd.producedArtifacts.add(MavenArtifact.forGav("com.foo:test:" + i));
            bd.persist();
        }

        Assertions.assertTrue(client.resources(ArtifactBuild.class).list().getItems().isEmpty());

        for (int i = 0; i < 10; ++i) {
            BuildQueue bq = new BuildQueue();
            bq.mavenArtifact = MavenArtifact.forGav("com.foo:test:" + i);
            bq.priority = true;
            bq.persistAndFlush();
            buildOrchestrator.checkBuildQueue();
        }
        Assertions.assertEquals(5, client.resources(ArtifactBuild.class).list().getItems().size());
    }
}

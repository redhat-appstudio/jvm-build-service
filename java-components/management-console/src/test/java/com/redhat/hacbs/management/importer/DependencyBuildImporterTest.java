package com.redhat.hacbs.management.importer;

import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.management.model.ScmRepository;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class DependencyBuildImporterTest {

    private static final Set<String> GAVS = Set.of(
            "io.quarkus.http:quarkus-http-vertx-backend:4.2.1",
            "io.quarkus.http:quarkus-http-websocket-vertx:4.2.1",
            "io.quarkus.http:quarkus-http-http-core:4.2.1",
            "io.quarkus.http:quarkus-http-core:4.2.1",
            "io.quarkus.http:quarkus-http-parent:4.2.1",
            "io.quarkus.http:quarkus-http-websocket-servlet:4.2.1",
            "io.quarkus.http:quarkus-http-websocket-parent:4.2.1",
            "io.quarkus.http:quarkus-http-servlet:4.2.1",
            "io.quarkus.http:quarkus-http-websocket-core:4.2.1");

    @Inject
    DependencyBuildImporter importer;

    @Test
    @TestTransaction
    public void testImport() throws Exception {
        ScmRepository repo = null;
        StoredDependencyBuild checkout = null;
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        DependencyBuild db = mapper.readerFor(DependencyBuild.class)
                .readValue(getClass().getClassLoader().getResource("dependencybuild/quarkus-http-2023-10-30.yaml"));
        importer.doImport(db);
        repo = ScmRepository.find("url", "https://github.com/quarkusio/quarkus-http.git").firstResult();
        Assertions.assertNotNull(repo);
        checkout = StoredDependencyBuild.find("buildIdentifier.repository", repo).firstResult();
        Assertions.assertEquals("4.2.1", checkout.version);
        Assertions.assertEquals("4.2.1", checkout.buildIdentifier.tag);
        Assertions.assertEquals("89edb4eac9bf57dabe59963f22549be682c33d38", checkout.buildIdentifier.hash);
        Assertions.assertEquals("2023-10-24T22:25:31Z", checkout.creationTimestamp.toString());
        Assertions.assertEquals(9, checkout.producedArtifacts.size());
        for (var i : checkout.producedArtifacts) {
            Assertions.assertTrue(GAVS.contains(i.gav()));
        }

        importer.doImport(db); //make sure that the second attempt does not throw

    }

}

package com.redhat.hacbs.management.resources;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import com.redhat.hacbs.management.importer.S3Importer;
import com.redhat.hacbs.management.internal.model.BuildSBOMDiscoveryInfo;
import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.management.model.ContainerImage;
import com.redhat.hacbs.management.model.DependencySet;
import com.redhat.hacbs.management.model.GithubActionsBuild;
import com.redhat.hacbs.management.model.IdentifiedDependency;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.MavenArtifactLabel;
import com.redhat.hacbs.management.model.ShadingDetails;
import com.redhat.hacbs.management.model.StoredArtifactBuild;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.JBSConfig;
import com.redhat.hacbs.resources.model.v1alpha1.JvmImageScan;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import io.quarkus.narayana.jta.TransactionSemantics;

@Path("admin")
@Transactional
public class AdminResource {

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    S3Importer s3Importer;

    @POST
    @Path("rebuild-all")
    public void rebuildAll() {
        for (StoredDependencyBuild sb : StoredDependencyBuild.<StoredDependencyBuild> listAll()) {
            StoredArtifactBuild sa = StoredArtifactBuild.find("buildIdentifier", sb.buildIdentifier).firstResult();
            BuildQueue.rebuild(sa.mavenArtifact, false, Map.of());
        }
    }

    @POST
    @Path("rebuild-failed")
    public void rebuildFailed() {
        for (StoredDependencyBuild sb : StoredDependencyBuild.<StoredDependencyBuild> list("succeeded", false)) {
            StoredArtifactBuild sa = StoredArtifactBuild.find("buildIdentifier", sb.buildIdentifier).firstResult();
            if (sa != null) {
                BuildQueue.rebuild(sa.mavenArtifact, false, Map.of());
            }
        }
    }

    @POST
    @Path("import-froms3")
    public void s3Import() {
        s3Importer.doImport();
    }

    @POST
    @Path("clean-out-database")
    public void cleanOutDatabase() {
        var scans = kubernetesClient.resources(JvmImageScan.class).list();
        for (var i : scans.getItems()) {
            kubernetesClient.resource(i).delete();
        }
        TransactionRunnerOptions runner = QuarkusTransaction.runner(TransactionSemantics.REQUIRE_NEW).timeout(60 * 60);
        runner.run(() -> {
            BuildQueue.deleteAll();
        });
        var builds = kubernetesClient.resources(ArtifactBuild.class).list();
        for (var i : builds.getItems()) {
            kubernetesClient.resource(i).delete();
        }
        runner.run(() -> {
            for (ShadingDetails i : ShadingDetails.<ShadingDetails> listAll()) {
                i.delete();
            }
        });
        runner.run(() -> {
            IdentifiedDependency.deleteAll();
        });
        runner.run(() -> {
            BuildSBOMDiscoveryInfo.deleteAll();
        });
        runner.run(() -> {
            BuildAttempt.deleteAll();
        });
        runner.run(() -> {
            StoredDependencyBuild.deleteAll();
        });
        runner.run(() -> {
            StoredArtifactBuild.deleteAll();
        });
        runner.run(() -> {
            ContainerImage.deleteAll();
            GithubActionsBuild.deleteAll();
            DependencySet.deleteAll();
            MavenArtifactLabel.deleteAll();
            MavenArtifact.deleteAll();
        });
        for (var i : kubernetesClient.resources(JBSConfig.class).list().getItems()) {
            kubernetesClient.resource(i).edit(new UnaryOperator<JBSConfig>() {
                @Override
                public JBSConfig apply(JBSConfig jbsConfig) {
                    if (jbsConfig.getMetadata().getAnnotations() == null) {
                        jbsConfig.getMetadata().setAnnotations(new HashMap<>());
                    }
                    jbsConfig.getMetadata().getAnnotations().put("jvmbuildservice.io/clear-cache", "true");
                    return jbsConfig;
                }
            });
        }
    }
}

package com.redhat.hacbs.management.resources;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import com.redhat.hacbs.management.internal.model.BuildSBOMDiscoveryInfo;
import com.redhat.hacbs.management.model.BuildAttempt;
import com.redhat.hacbs.management.model.BuildQueue;
import com.redhat.hacbs.management.model.ContainerImage;
import com.redhat.hacbs.management.model.DependencySet;
import com.redhat.hacbs.management.model.GithubActionsBuild;
import com.redhat.hacbs.management.model.IdentifiedDependency;
import com.redhat.hacbs.management.model.MavenArtifact;
import com.redhat.hacbs.management.model.MavenArtifactLabel;
import com.redhat.hacbs.management.model.StoredArtifactBuild;
import com.redhat.hacbs.management.model.StoredDependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
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
            BuildQueue.rebuild(sa.mavenArtifact, false, Map.of());
        }
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
            BuildAttempt.deleteAll();
        });
        runner.run(() -> {
            StoredDependencyBuild.deleteAll();
        });
        runner.run(() -> {
            StoredArtifactBuild.deleteAll();
        });
        runner.run(() -> {
            BuildSBOMDiscoveryInfo.deleteAll();
            ContainerImage.deleteAll();
            GithubActionsBuild.deleteAll();
            IdentifiedDependency.deleteAll();
            DependencySet.deleteAll();
            MavenArtifactLabel.deleteAll();
            MavenArtifact.deleteAll();
        });
    }
}

package com.redhat.hacbs.cli.builds;

import static com.github.stefanbirkner.systemlambda.SystemLambda.tapSystemOut;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.redhat.hacbs.cli.TestComponentManager;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class BuildListCommandTest extends TestComponentManager {

    @Test
    public void testList() throws Exception {
        KubernetesClient kubernetesClient = server.getClient();

        ArtifactBuild ab = kubernetesClient
                .resource(createArtifactBuild("commons.net.3.6-65df3c98", "commons-net:commons-net:3.6",
                        ModelConstants.ARTIFACT_BUILD_COMPLETE))
                .create();
        DependencyBuild db = createDependencyBuild(
                ab,
                "b65da343c6ff99b4d15da62b349d9abb", "commons.net.3.6-65df3c98",
                "3.6",
                "2c9b32abf0c0f5041d328a6034e30650b493eb4d",
                "https://github.com/apache/commons-net.git",
                "NET_3_6",
                ModelConstants.DEPENDENCY_BUILD_COMPLETE);
        kubernetesClient.resource(db).create();

        ab = kubernetesClient
                .resource(createArtifactBuild("commons.net.3.7-65df3c98", "commons-net:commons-net:3.7",
                        ModelConstants.ARTIFACT_BUILD_COMPLETE))
                .create();
        db = createDependencyBuild(
                ab,
                "b8febbb91585477cb5ca8822fce5bf20", "commons.net.3.6-65df3c98",
                "3.7",
                "2c9b32abf0c0f5041d328a6034e30650b493eb4d",
                "https://github.com/apache/commons-net.git",
                "NET_3_7",
                ModelConstants.DEPENDENCY_BUILD_FAILED);
        kubernetesClient.resource(db).create();

        ab = kubernetesClient
                .resource(createArtifactBuild("commons.net.3.8-65df3c98", "commons-net:commons-net:3.8",
                        ModelConstants.ARTIFACT_BUILD_COMPLETE))
                .create();
        db = createDependencyBuild(
                ab,
                "2c9b32abf0c0f5041d328a6034e30650b493eb4d", "commons.net.3.6-65df3c98",
                "3.8",
                "2c9b32abf0c0f5041d328a6034e30650b493eb4d",
                "https://github.com/apache/commons-net.git",
                "NET_3_8",
                ModelConstants.DEPENDENCY_BUILD_BUILDING);
        kubernetesClient.resource(db).create();

        BuildListCommand blc = new BuildListCommand();
        String out = tapSystemOut(blc::run);
        assertThat(out.lines().toList()).containsExactly(
                "apache/commons-net.git@NET_3_6   DependencyBuildStateComplete   b65da343c6ff99b4d15da62b349d9abb",
                "apache/commons-net.git@NET_3_7   DependencyBuildStateFailed     b8febbb91585477cb5ca8822fce5bf20",
                "apache/commons-net.git@NET_3_8   DependencyBuildStateBuilding   2c9b32abf0c0f5041d328a6034e30650b493eb4d");

        blc.failed = true;
        out = tapSystemOut(blc::run);
        assertThat(out.lines().toList()).containsExactly(
                "apache/commons-net.git@NET_3_7   DependencyBuildStateFailed   b8febbb91585477cb5ca8822fce5bf20");

        blc.failed = false;
        blc.building = true;
        out = tapSystemOut(blc::run);
        assertThat(out.lines().toList()).containsExactly(
                "apache/commons-net.git@NET_3_8   DependencyBuildStateBuilding   2c9b32abf0c0f5041d328a6034e30650b493eb4d");
    }
}

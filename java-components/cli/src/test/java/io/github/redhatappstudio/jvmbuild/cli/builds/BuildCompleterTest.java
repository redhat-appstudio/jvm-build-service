package io.github.redhatappstudio.jvmbuild.cli.builds;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.redhat.hacbs.common.tools.completer.BuildCompleter;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.TestComponentManager;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class BuildCompleterTest extends TestComponentManager {

    @Test
    void testCreateNames() {
        String gav = "commons-net:commons-net:3.6";
        String abName = "commons.net.3.6-65df3c98";
        KubernetesClient kubernetesClient = server.getClient();
        ArtifactBuild ab = kubernetesClient.resource(createArtifactBuild(
                abName,
                gav, ModelConstants.ARTIFACT_BUILD_COMPLETE)).create();

        DependencyBuild db = createDependencyBuild(
                ab,
                "b65da343c6ff99b4d15da62b349d9abb",
                abName,
                "3.6",
                "2c9b32abf0c0f5041d328a6034e30650b493eb4d",
                "https://github.com/apache/commons-net.git",
                "NET_3_6",
                ModelConstants.DEPENDENCY_BUILD_COMPLETE);
        kubernetesClient.resource(db).create();

        var result = BuildCompleter.createNames();
        assertThat(result).hasSize(1);
        assertThat(result).containsKey("apache/commons-net.git@NET_3_6");
    }
}

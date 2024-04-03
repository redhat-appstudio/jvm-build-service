package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import static com.github.stefanbirkner.systemlambda.SystemLambda.tapSystemOut;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.TestComponentManager;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ArtifactListCommandTest extends TestComponentManager {
    @Test
    public void testList() throws Exception {
        KubernetesClient kubernetesClient = server.getClient();

        kubernetesClient
                .resource(createArtifactBuild("commons.net.3.6-65df3c98", "commons-net:commons-net:3.6",
                        ModelConstants.ARTIFACT_BUILD_COMPLETE))
                .create();
        kubernetesClient
                .resource(createArtifactBuild("commons.net.3.7-65df3c98", "commons-net:commons-net:3.7",
                        ModelConstants.ARTIFACT_BUILD_FAILED))
                .create();
        kubernetesClient
                .resource(createArtifactBuild("commons.net.3.8-65df3c98", "commons-net:commons-net:3.8",
                        ModelConstants.ARTIFACT_BUILD_BUILDING))
                .create();

        ArtifactListCommand alc = new ArtifactListCommand();
        String out = tapSystemOut(alc::run);
        assertThat(out.lines().toList()).containsExactly(
                "commons-net:commons-net:3.6   ArtifactBuildComplete   commons.net.3.6-65df3c98",
                "commons-net:commons-net:3.7   ArtifactBuildFailed     commons.net.3.7-65df3c98",
                "commons-net:commons-net:3.8   ArtifactBuildBuilding   commons.net.3.8-65df3c98");

        alc.failed = true;
        out = tapSystemOut(alc::run);
        assertThat(out.lines().toList())
                .containsExactly("commons-net:commons-net:3.7   ArtifactBuildFailed   commons.net.3.7-65df3c98");

        alc.failed = false;
        alc.building = true;
        out = tapSystemOut(alc::run);
        assertThat(out.lines().toList())
                .containsExactly("commons-net:commons-net:3.8   ArtifactBuildBuilding   commons.net.3.8-65df3c98");
    }
}

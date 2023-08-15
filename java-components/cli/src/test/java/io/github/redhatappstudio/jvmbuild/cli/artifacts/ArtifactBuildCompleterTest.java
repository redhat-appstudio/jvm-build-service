package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.TestComponentManager;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ArtifactBuildCompleterTest extends TestComponentManager {

    @Test
    void testCreateNames()
            throws JsonProcessingException {
        String gav = "commons-net:commons-net:3.6";
        String abName = "commons.net.3.6-65df3c98";
        KubernetesClient kubernetesClient = server.getClient();
        ArtifactBuild ab = kubernetesClient.resource(createArtifactBuild(
                abName,
                gav, ModelConstants.ARTIFACT_BUILD_COMPLETE)).create();

        var result = ArtifactBuildCompleter.createNames();

        assertEquals(1, result.size());
        assertTrue(result.containsKey(abName));

        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.writeValueAsString(ab), mapper.writeValueAsString(result.get(abName)));
    }
}

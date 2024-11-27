package com.redhat.hacbs.driver;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import org.apache.commons.io.IOUtils;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.hacbs.driver.clients.IndyService;
import com.redhat.hacbs.driver.clients.IndyTokenRequestDTO;
import com.redhat.hacbs.driver.clients.IndyTokenResponseDTO;
import com.redhat.hacbs.driver.dto.BuildRequest;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.tekton.client.TektonClient;
import io.fabric8.tekton.pipeline.v1.ParamBuilder;
import io.fabric8.tekton.pipeline.v1.PipelineRun;
import io.quarkus.oidc.client.OidcClient;
import lombok.Setter;

@RequestScoped
public class Driver {

    private static final Logger logger = LoggerFactory.getLogger(Driver.class);

    @Inject
    OidcClient oidcClient;

    @RestClient
    IndyService indyService;

    @Inject
    KubernetesClient client;

    @Setter
    private String accessToken;

    @Setter
    private String quayRepo = "quay.io/redhat-user-workloads/konflux-jbs-pnc-tenant/jvm-build-service/build-request-processor:latest";

    @Setter
    private String processor = "quay.io/redhat-user-workloads-stage/pnc-devel-tenant/pnc";

    @ConfigProperty(name = "build-driver.pipeline")
    Optional<String> customPipeline;

    public void create(BuildRequest buildRequest) {
        IndyTokenResponseDTO tokenResponseDTO = new IndyTokenResponseDTO(accessToken);

        if (isEmpty(accessToken)) {
            logger.info("Establishing token from Indy using clientId {}",
                    ConfigProvider.getConfig().getConfigValue("quarkus.oidc.client-id").getValue());
            tokenResponseDTO = indyService.getAuthToken(
                    new IndyTokenRequestDTO(buildRequest.getRepositoryBuildContentId()),
                    "Bearer " + getFreshAccessToken());
        }

        Map<String, String> templateProperties = new HashMap<>();
        templateProperties.put("ACCESS_TOKEN", tokenResponseDTO.getToken());
        templateProperties.put("BUILD_ID", buildRequest.getRepositoryBuildContentId());
        templateProperties.put("BUILD_SCRIPT", buildRequest.getBuildScript());
        templateProperties.put("BUILD_TOOL", buildRequest.getBuildTool());
        templateProperties.put("BUILD_TOOL_VERSION", buildRequest.getBuildToolVersion());
        templateProperties.put("JAVA_VERSION", buildRequest.getJavaVersion());
        templateProperties.put("MVN_REPO_DEPENDENCIES_URL", buildRequest.getRepositoryDependencyUrl());
        templateProperties.put("MVN_REPO_DEPLOY_URL", buildRequest.getRepositoryDeployUrl());
        templateProperties.put("QUAY_REPO", quayRepo);
        templateProperties.put("RECIPE_IMAGE", buildRequest.getRecipeImage());
        templateProperties.put("JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE", processor);
        templateProperties.put("REVISION", buildRequest.getScmRevision());
        templateProperties.put("URL", buildRequest.getScmUrl());

        PipelineRun pipelineRun = null;
        try {
            var tc = client.adapt(TektonClient.class);
            // Various ways to create the initial PipelineRun object. We can use an objectmapper,
            // client.getKubernetesSerialization() or the load calls on the Fabric8 objects.
            if (customPipeline.isEmpty()) {
                pipelineRun = tc.v1().pipelineRuns()
                        .load(IOUtils.resourceToURL("pipeline.yaml", Thread.currentThread().getContextClassLoader())).item();
            } else {
                pipelineRun = tc.v1().pipelineRuns().load(Path.of(customPipeline.get()).toFile()).item();
            }
        } catch (IOException e) {
            e.printStackTrace();
            // TODO: process
        }
        pipelineRun = pipelineRun.edit().editOrNewSpec()
                .addAllToParams(templateProperties.entrySet().stream()
                        .map(t -> new ParamBuilder().withName(t.getKey()).withNewValue(t.getValue()).build()).toList())
                .editFirstTaskRunSpec()
                .editFirstStepSpec()
                .editComputeResources()
                .addToLimits("memory", new Quantity(buildRequest.getPodMemoryOverride()))
                .addToRequests("memory", new Quantity(buildRequest.getPodMemoryOverride()))
                .endComputeResources()
                .endStepSpec()
                .endTaskRunSpec()
                .endSpec().build();

        System.err.println("### Got p " + pipelineRun);
        // PipelineRun run = createModelNode(pipeline, templateProperties, PipelineRun.class);
        //run.getSpec().setParams();
        var created = client.resource(pipelineRun).inNamespace(buildRequest.getNamespace()).create();
    }

    /**
     * Get a fresh access token for the service account. This is done because we want to get a super-new token to be
     * used since we're not entirely sure when the http request will be done inside the completablefuture.
     *
     * @return fresh access token
     */
    public String getFreshAccessToken() {
        return oidcClient.getTokens().await().indefinitely().getAccessToken();
    }
}

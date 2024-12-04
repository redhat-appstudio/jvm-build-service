package com.redhat.hacbs.driver;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.redhat.hacbs.driver.dto.BuildResponse;
import com.redhat.hacbs.driver.dto.CancelRequest;

import io.fabric8.knative.internal.pkg.apis.Condition;
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
    @ConfigProperty(name = "konflux-build-driver.konflux-processor")
    String processor;

    @Setter
    @ConfigProperty(name = "konflux-build-driver.quay-repo")
    String quayRepo;

    @ConfigProperty(name = "konflux-build-driver.pipeline-resolver")
    String resolverTarget;

    public BuildResponse create(BuildRequest buildRequest) {
        IndyTokenResponseDTO tokenResponseDTO = new IndyTokenResponseDTO(accessToken);

        if (isEmpty(accessToken)) {
            logger.info("Establishing token from Indy using clientId {}",
                    ConfigProvider.getConfig().getConfigValue("quarkus.oidc.client-id").getValue());
            tokenResponseDTO = indyService.getAuthToken(
                    new IndyTokenRequestDTO(buildRequest.repositoryBuildContentId()),
                    "Bearer " + getFreshAccessToken());
        }

        Map<String, String> templateProperties = new HashMap<>();
        templateProperties.put("ACCESS_TOKEN", tokenResponseDTO.token());
        templateProperties.put("BUILD_ID", buildRequest.repositoryBuildContentId());
        templateProperties.put("BUILD_SCRIPT", buildRequest.buildScript());
        templateProperties.put("BUILD_TOOL", buildRequest.buildTool());
        templateProperties.put("BUILD_TOOL_VERSION", buildRequest.buildToolVersion());
        templateProperties.put("JAVA_VERSION", buildRequest.javaVersion());
        templateProperties.put("MVN_REPO_DEPENDENCIES_URL", buildRequest.repositoryDependencyUrl());
        templateProperties.put("MVN_REPO_DEPLOY_URL", buildRequest.repositoryDeployUrl());
        templateProperties.put("QUAY_REPO", quayRepo);
        templateProperties.put("RECIPE_IMAGE", buildRequest.recipeImage());
        templateProperties.put("JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE", processor);
        templateProperties.put("REVISION", buildRequest.scmRevision());
        templateProperties.put("URL", buildRequest.scmUrl());

        PipelineRun pipelineRun = null;
        try {
            var tc = client.adapt(TektonClient.class);
            // Various ways to create the initial PipelineRun object. We can use an objectmapper,
            // client.getKubernetesSerialization() or the load calls on the Fabric8 objects.
            pipelineRun = tc.v1().pipelineRuns()
                    .load(IOUtils.resourceToURL("pipeline.yaml", Thread.currentThread().getContextClassLoader())).item();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        pipelineRun = pipelineRun.edit().editOrNewSpec()
                .editPipelineRef()
                .editFirstParam().editOrNewValue().withStringVal(resolverTarget).endValue()
                .endParam()
                .endPipelineRef()
                .addAllToParams(templateProperties.entrySet().stream()
                        .map(t -> new ParamBuilder().withName(t.getKey()).withNewValue(t.getValue()).build()).toList())
                .editFirstTaskRunSpec()
                .editFirstStepSpec()
                .editComputeResources()
                .addToLimits("memory", new Quantity(buildRequest.podMemoryOverride()))
                .addToRequests("memory", new Quantity(buildRequest.podMemoryOverride()))
                .endComputeResources()
                .endStepSpec()
                .endTaskRunSpec()
                .endSpec().build();

        var created = client.resource(pipelineRun).inNamespace(buildRequest.namespace()).create();

        return BuildResponse.builder().namespace(buildRequest.namespace()).pipelineId(created.getMetadata().getName()).build();
    }

    public void cancel(CancelRequest request) {
        var tc = client.adapt(TektonClient.class);
        var pipeline = tc.v1beta1().pipelineRuns().inNamespace(request.namespace()).withName(request.pipelineId()).get();

        logger.info("Retrieved pipeline {}", pipeline.getMetadata().getName());

        List<Condition> conditions = new ArrayList<>();
        // https://tekton.dev/docs/pipelines/pipelineruns/#monitoring-execution-status
        Condition cancelCondition = new Condition();
        cancelCondition.setType("Succeeded");
        cancelCondition.setStatus("False");
        // https://github.com/tektoncd/community/blob/main/teps/0058-graceful-pipeline-run-termination.md
        cancelCondition.setReason("CancelledRunFinally");
        cancelCondition.setMessage("The PipelineRun was cancelled");
        conditions.add(cancelCondition);

        pipeline.getStatus().setConditions(conditions);

        tc.v1beta1().pipelineRuns().inNamespace(request.namespace()).resource(pipeline).updateStatus();
    }

    /**
     * Get a fresh access token for the service account. This is done because we want to get a
     * super-new token to be used since we're not entirely sure when the http request will be done.
     *
     * @return fresh access token
     */
    public String getFreshAccessToken() {
        return oidcClient.getTokens().await().indefinitely().getAccessToken();
    }
}

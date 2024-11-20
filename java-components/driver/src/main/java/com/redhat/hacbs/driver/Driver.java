package com.redhat.hacbs.driver;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringSubstitutor;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.redhat.hacbs.driver.clients.IndyService;
import com.redhat.hacbs.driver.clients.IndyTokenRequestDTO;
import com.redhat.hacbs.driver.clients.IndyTokenResponseDTO;
import com.redhat.hacbs.driver.dto.BuildRequest;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.tekton.pipeline.v1.PipelineRun;
import io.quarkus.oidc.client.OidcClient;

@RequestScoped
public class Driver {

    private static final Logger logger = LoggerFactory.getLogger(Driver.class);

    @Inject
    OidcClient oidcClient;

    @RestClient
    IndyService indyService;

    // TODO: Could use KubernetesClient or OpenShiftClient
    @Inject
    KubernetesClient client;

    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private String accessToken;

    public void addAccessToken(String accessToken) {
        System.err.println("### oidc " + oidcClient);
        this.accessToken = accessToken;
    }

    public void create(BuildRequest buildRequest) throws IOException {
        IndyTokenResponseDTO tokenResponseDTO = new IndyTokenResponseDTO(accessToken);

        if (isEmpty(accessToken)) {
            tokenResponseDTO = indyService.getAuthToken(
                    new IndyTokenRequestDTO(buildRequest.getRepositoryBuildContentId()),
                    "Bearer " + getFreshAccessToken());
        }

        Map<String, String> templateProperties = new HashMap<>();
        templateProperties.put("URL", buildRequest.getScmUrl());
        templateProperties.put("REVISION", buildRequest.getScmRevision());
        templateProperties.put("BUILD_TOOL", buildRequest.getBuildTool());
        templateProperties.put("BUILD_TOOL_VERSION", buildRequest.getBuildToolVersion());
        templateProperties.put("JAVA_VERSION", buildRequest.getJavaVersion());
        templateProperties.put("BUILD_SCRIPT", buildRequest.getBuildScript());
        templateProperties.put("MVN_REPO_DEPLOY_URL", buildRequest.getRepositoryDeployUrl());
        templateProperties.put("MVN_REPO_DEPENDENCIES_URL", buildRequest.getRepositoryDependencyUrl());
        templateProperties.put("ACCESS_TOKEN", tokenResponseDTO.getToken());
        templateProperties.put("BUILD_ID", buildRequest.getRepositoryBuildContentId());

        String pipeline = IOUtils.resourceToString("pipeline.yaml", StandardCharsets.UTF_8,
                Thread.currentThread().getContextClassLoader());

        PipelineRun run = createModelNode(pipeline, templateProperties, PipelineRun.class);

        var created = client.resource(run).inNamespace(buildRequest.getNamespace()).create();

        System.err.println("### " + created);
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

    private <T> T createModelNode(String resourceDefinition, Map<String, String> properties, Class<T> clazz) {
        String definition = StringSubstitutor.replace(resourceDefinition, properties, "%{", "}");

        if (logger.isTraceEnabled()) {
            logger.trace("Node definition: {}", definition);
        }

        try {
            return yamlMapper.readValue(definition, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}

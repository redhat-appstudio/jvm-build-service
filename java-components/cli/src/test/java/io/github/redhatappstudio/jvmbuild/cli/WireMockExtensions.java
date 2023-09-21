package io.github.redhatappstudio.jvmbuild.cli;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class WireMockExtensions implements QuarkusTestResourceLifecycleManager {
    public static final String RESULT_UID = "600f6ecc-342e-4793-94ce-6ac79d3891b0";
    public static final String LOG_UID = "3a0c8e9b-8ad6-4479-91ff-6bfcf4bb1c33";
    private WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {
        wireMockServer = new WireMockServer(wireMockConfig().httpsPort(8043));
        wireMockServer.start();

        wireMockServer.stubFor(
                get(urlEqualTo("/apis/results.tekton.dev/v1alpha2/parents/test-jvm-namespace/results/" + RESULT_UID + "/logs/"
                        + LOG_UID))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody(
                                        """
                                                {
                                                  "result": {
                                                    "name": "test-jvm-namespace/results/d17cc6d0-b588-4baa-82d3-fd7d562c26ed/logs/085afabc-f3ec-3252-b99f-9c199395338c",
                                                    "data": "VGhpcyBpcyBteSA="
                                                  }
                                                }
                                                {
                                                  "result": {
                                                    "name": "test-jvm-namespace/results/f60adcb4-801b-4ba6-9fd3-8bccab9b4b93/logs/79e500b2-1961-3d14-b2b5-c97b19623ced]",
                                                    "data": "cG9kIGxvZyEK"
                                                  }
                                                }""")));

        return Map.of("quarkus.rest-client.wiremockextensions.url", wireMockServer.baseUrl());
    }

    @Override
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Override
    public void inject(TestInjector testInjector) {
        testInjector.injectIntoFields(wireMockServer, new TestInjector.MatchesType(WireMockServer.class));
    }
}

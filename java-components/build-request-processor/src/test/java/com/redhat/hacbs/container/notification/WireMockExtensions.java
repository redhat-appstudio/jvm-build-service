package com.redhat.hacbs.container.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class WireMockExtensions implements QuarkusTestResourceLifecycleManager {
    private WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {
        wireMockServer = new WireMockServer(wireMockConfig().notifier(new ConsoleNotifier(true)));
        wireMockServer.start();

        wireMockServer.stubFor(
                put(urlEqualTo("/internal/completed"))
                    .withRequestBody(
                        equalToJson("{\"status\":\"Succeeded\",\"buildId\":\"1234\",\"completionCallback\":null}"))
                        .willReturn(aResponse()
                            .withStatus(200)));

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

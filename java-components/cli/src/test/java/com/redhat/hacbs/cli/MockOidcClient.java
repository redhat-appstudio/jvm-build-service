package com.redhat.hacbs.cli;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;

/**
 * From <a href="https://github.com/project-ncl/build-driver/blob/main/src/test/java/org/jboss/pnc/builddriver/MockOidcClient.java">PNC BuildDriver</a>
 */
@Mock
public class MockOidcClient implements OidcClient {

    @Override
    public Uni<Tokens> getTokens(Map<String, String> additionalGrantParameters) {
        return Uni.createFrom()
                .item(new Tokens("accessToken", 1L, Duration.of(5, ChronoUnit.MINUTES), "refreshToken", 1L, null, null));
    }

    @Override
    public Uni<Tokens> refreshTokens(String refreshToken, Map<String, String> additionalGrantParameters) {
        return null;
    }

    @Override
    public Uni<Boolean> revokeAccessToken(String accessToken, Map<String, String> additionalParameters) {
        return null;
    }

    @Override
    public void close() throws IOException {

    }
}

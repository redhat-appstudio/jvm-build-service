package com.redhat.hacbs.domainproxy.server;

import static com.redhat.hacbs.domainproxy.common.CommonIOUtil.LOCALHOST;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class ExternalProxyVerticleTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("server-http-port", "2001", "proxy-target-whitelist", LOCALHOST);
    }
}

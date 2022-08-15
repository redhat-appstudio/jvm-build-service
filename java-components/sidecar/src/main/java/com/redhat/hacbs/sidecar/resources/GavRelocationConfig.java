package com.redhat.hacbs.sidecar.resources;

import java.util.Map;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "gav.relocation")
public interface GavRelocationConfig {
    Map<String, String> pattern();
}

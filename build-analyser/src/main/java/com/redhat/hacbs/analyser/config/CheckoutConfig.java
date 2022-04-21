package com.redhat.hacbs.analyser.config;

import io.smallrye.config.ConfigMapping;

import java.nio.file.Path;

@ConfigMapping(prefix = "checkout")
public interface CheckoutConfig {

    Path path();

}

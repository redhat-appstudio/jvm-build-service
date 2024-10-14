package com.redhat.hacbs.container.analyser.build.ant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;

import org.apache.ivy.Ivy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class IvyUtilsTest {
    private static final String IVYSETTINGS_XML = "ivysettings.xml";

    private static final String PROXY_URL = "cache-url";

    private static final String PROXY_URL_VALUE = "$(params.PROXY_URL)";

    private static final String DEFAULT_PATTERN = "default-pattern";

    private static final String DEFAULT_PATTERN_VALUE = "[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]";

    private static final String LOCAL_PATTERN = "local-pattern";

    private static final String LOCAL_PATTERN_VALUE = System.getProperty("user.home")
            + "/.m2/repository/[organisation]/[module]/[revision]/[module]-[revision](-[classifier]).[ext]";

    private static final String DEFAULT_RESOLVER = "default";

    private static final String DEFAULT_CHAIN = "defaultChain";

    private static final String LOCAL_RESOLVER = "local";

    private static Ivy ivy;

    @BeforeAll
    static void loadIvy() throws URISyntaxException {
        var url = IvyUtilsTest.class.getResource(IVYSETTINGS_XML);
        assertThat(url).isNotNull();
        var uri = url.toURI();
        var settingsFile = Path.of(uri);
        ivy = IvyUtils.loadIvy(settingsFile);
    }

    @Test
    void testIvySettings() {
        var settings = ivy.getSettings();
        settings.validate();
        var cacheUrl = settings.getVariable(PROXY_URL);
        assertThat(cacheUrl).isEqualTo(PROXY_URL_VALUE);
        var defaultPattern = settings.getVariable(DEFAULT_PATTERN);
        assertThat(defaultPattern).isEqualTo(DEFAULT_PATTERN_VALUE);
        var localPattern = settings.getVariable(LOCAL_PATTERN);
        assertThat(localPattern).isEqualTo(LOCAL_PATTERN_VALUE);
        var defaultResolver = settings.getDefaultResolver();
        var name = defaultResolver.getName();
        assertThat(name).isEqualTo(DEFAULT_CHAIN);
        var resolvers = settings.getResolvers();
        assertThat(resolvers).hasSize(3).extracting("name")
                .containsExactlyInAnyOrder(DEFAULT_RESOLVER, DEFAULT_CHAIN, LOCAL_RESOLVER);
    }
}

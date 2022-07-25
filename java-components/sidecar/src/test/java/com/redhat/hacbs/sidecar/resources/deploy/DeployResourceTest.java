package com.redhat.hacbs.sidecar.resources.deploy;

import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DeployResourceTest {

    @Test
    public void testShouldIgnore() {
        Assertions.assertTrue(DeployerUtil.shouldIgnore(Set.of("quarkus-cli"),
                "./io/quarkus/quarkus-cli/2.7.6.Final/quarkus-cli-2.7.6.Final-runner.jar"));
    }
}

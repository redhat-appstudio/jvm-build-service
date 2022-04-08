package com.redhat.hacbs.artifactcache.health;

import java.util.Map;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import com.redhat.hacbs.artifactcache.services.BuildPolicy;

@Liveness
@ApplicationScoped
public class BasicHealthCheck implements HealthCheck {

    public static final String BASIC_HEALTH_CHECK = "Basic health check";

    final Map<String, BuildPolicy> buildPolicies;

    public BasicHealthCheck(Map<String, BuildPolicy> repositories) {
        this.buildPolicies = repositories;
    }

    @Override
    public HealthCheckResponse call() {
        if (buildPolicies.isEmpty()) {
            return HealthCheckResponse.down(BASIC_HEALTH_CHECK);
        }
        return HealthCheckResponse.up(BASIC_HEALTH_CHECK);
    }
}

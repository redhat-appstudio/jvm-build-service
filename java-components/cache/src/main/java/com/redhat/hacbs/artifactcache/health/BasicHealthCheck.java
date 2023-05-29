package com.redhat.hacbs.artifactcache.health;

import java.lang.management.ManagementFactory;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

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
            return HealthCheckResponse.named(BASIC_HEALTH_CHECK)
                    .down()
                    .withData("reason", "No policies defined")
                    .build();
        }
        return HealthCheckResponse.named(BASIC_HEALTH_CHECK)
                .up()
                // In seconds
                .withData("uptime", ManagementFactory.getRuntimeMXBean().getUptime() / 1000)
                .withData("startTime", ManagementFactory.getRuntimeMXBean().getStartTime())
                .build();
    }
}

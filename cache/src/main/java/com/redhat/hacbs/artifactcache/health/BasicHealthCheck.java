package com.redhat.hacbs.artifactcache.health;

import java.util.List;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import com.redhat.hacbs.artifactcache.services.Repository;

@Liveness
@ApplicationScoped
public class BasicHealthCheck implements HealthCheck {

    public static final String BASIC_HEALTH_CHECK = "Basic health check";

    final List<Repository> repositories;

    public BasicHealthCheck(List<Repository> repositories) {
        this.repositories = repositories;
    }

    @Override
    public HealthCheckResponse call() {
        if (repositories.isEmpty()) {
            return HealthCheckResponse.down(BASIC_HEALTH_CHECK);
        }
        return HealthCheckResponse.up(BASIC_HEALTH_CHECK);
    }
}

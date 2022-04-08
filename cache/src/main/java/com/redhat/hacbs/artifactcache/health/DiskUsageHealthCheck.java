package com.redhat.hacbs.artifactcache.health;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * This takes a very simple approach to the disk becoming full, once the
 * threshold is exceeded then the pod is considered
 * to be unhealthy.
 * <p>
 * As long as the system is configured with enough disk space this should not be
 * an issue.
 * <p>
 * This is not intended to be a long term solution.
 */
@Liveness
@ApplicationScoped
public class DiskUsageHealthCheck implements HealthCheck {

    private static final String DISK_SPACE = "Disk Space";
    private final FileStore fileStore;
    private final long requiredFreeSpace;

    public DiskUsageHealthCheck(@ConfigProperty(name = "cache-path") Path path,
            @ConfigProperty(name = "cache-disk-percentage-allowed") double allowedPercentage) throws Exception {
        fileStore = Files.getFileStore(path);
        requiredFreeSpace = (long) (fileStore.getTotalSpace() * (1 - allowedPercentage));
    }

    @Override
    public HealthCheckResponse call() {
        try {
            if (fileStore.getUsableSpace() < requiredFreeSpace) {
                return HealthCheckResponse.named(DISK_SPACE)
                        .down()
                        .withData("reason", "Insufficient disk space available")
                        .build();
            }
            return HealthCheckResponse.named(DISK_SPACE)
                    .up()
                    // In MB
                    .withData("space", fileStore.getUsableSpace() / 1024 / 1024)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

package com.redhat.hacbs.artifactcache.health;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ExecutorRecorder;

@Liveness
public class ThreadPoolHealthCheck implements HealthCheck {
    @Override
    public HealthCheckResponse call() {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        ExecutorRecorder.getCurrent().execute(new Runnable() {
            @Override
            public void run() {
                cf.complete(null);
            }
        });
        try {
            cf.get(10, TimeUnit.SECONDS);
            return HealthCheckResponse.up("Thread Pool");
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Log.errorf(e, "Thread pool health check failed, dumping all threads");
            for (Map.Entry<Thread, StackTraceElement[]> i : Thread.getAllStackTraces().entrySet()) {
                System.err.println("\n");
                System.err.println(i.toString());
                System.err.println("\n");
                for (StackTraceElement j : i.getValue()) {
                    System.err.println(j);
                }
            }
            return HealthCheckResponse.down("Thread Pool");
        }
    }
}

package com.redhat.hacbs.container.notification;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.pnc.api.dto.Request;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "notify")
public class NotifyCommand implements Runnable {

    @CommandLine.Option(names = "--build-id", required = true)
    String buildId;

    @CommandLine.Option(names = "--status", required = true)
    String status;

    @CommandLine.Option(names = "--context", required = true)
    String context;

    @CommandLine.Option(names = "--request-timeout")
    int requestTimeout = 15;

    // TODO: do we need this...
    @ConfigProperty(name = "access.token")
    Optional<String> accessToken;

    @Inject
    ObjectMapper objectMapper;

    public void run() {
        try {

            if (isEmpty(context)) {
                Log.infof("No callback configured ; unable to notify.");
                return;
            }

            Request callback = objectMapper.readValue(context, Request.class );

            Log.infof("Notification for build %s with status %s and callback %s", buildId, status, callback);
            PipelineNotification notification = PipelineNotification.builder().buildId(buildId).status(status).completionCallback((Request) callback.getAttachment()).build();
            String body = objectMapper.writeValueAsString(notification);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(callback.getUri())
                .method(callback.getMethod().name(), HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(requestTimeout));
            callback.getHeaders().forEach(h -> builder.header(h.getName(), h.getValue()));


            HttpRequest request = builder.build();
            // TODO: Retry? Send async? Some useful mutiny examples from quarkus in https://gist.github.com/cescoffier/e9abce907a1c3d05d70bea3dae6dc3d5
            HttpResponse<String> response;
            try (HttpClient httpClient = HttpClient.newHttpClient()) {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }
            Log.infof("Response %s", response);

        } catch (Exception e) {
            Log.error("Notification failed", e);
            throw new RuntimeException(e);
        }
    }
}

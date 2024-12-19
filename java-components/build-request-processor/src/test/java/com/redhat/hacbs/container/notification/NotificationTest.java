package com.redhat.hacbs.container.notification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.logging.LogRecord;

import javax.ws.rs.core.MediaType;

import org.jboss.pnc.api.constants.HttpHeaders;
import org.jboss.pnc.api.dto.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

import io.quarkus.test.LogCollectingTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = LogCollectingTestResource.class, restrictToAnnotatedClass = true, initArgs = @ResourceArg(name = LogCollectingTestResource.LEVEL, value = "FINE"))
@QuarkusTestResource(WireMockExtensions.class)
public class NotificationTest {

    private WireMockServer wireMockServer;

    @BeforeEach
    public void clearLogs() {
        LogCollectingTestResource.current().clear();
    }

    @Test
    public void testNoNotify() {
        NotifyCommand notifyCommand = new NotifyCommand();
        notifyCommand.status = "Succeeded";
        notifyCommand.buildId = "1234";
        notifyCommand.run();
        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream()
            .anyMatch(r -> LogCollectingTestResource.format(r).contains("No callback configured ; unable to notify")));
    }

    @Test
    public void testNotify() throws IOException, URISyntaxException {

        Request request = Request.builder()
            .method(Request.Method.PUT)
            .header(new Request.Header(HttpHeaders.CONTENT_TYPE_STRING, MediaType.APPLICATION_JSON))
            .attachment(null)
            .uri(new URI(wireMockServer.baseUrl() + "/internal/completed"))
            .build();

        NotifyCommand notifyCommand = new NotifyCommand();
        notifyCommand.status = "Succeeded";
        notifyCommand.buildId = "1234";
        notifyCommand.objectMapper = new ObjectMapper();
        notifyCommand.context = notifyCommand.objectMapper.writeValueAsString(request);
        notifyCommand.run();

        List<LogRecord> logRecords = LogCollectingTestResource.current().getRecords();
        assertTrue(logRecords.stream()
            .anyMatch(r -> LogCollectingTestResource.format(r).contains("Response (PUT http://localhost:8080/internal/completed) 200")));
    }
}

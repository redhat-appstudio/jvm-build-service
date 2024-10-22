package com.redhat.hacbs.domainproxy;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.quarkus.logging.Log;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

@ApplicationScoped
public class ExternalProxyVerticle extends AbstractVerticle {

    static final int HTTPS_PORT = 443;

    @Inject
    @ConfigProperty(name = "server-http-port")
    int httpServerPort;

    @Inject
    @ConfigProperty(name = "proxy-target-whitelist")
    Set<String> proxyTargetWhitelist;

    private final WebClient webClient;
    private final NetClient netClient;
    private final HttpServer httpServer;

    public ExternalProxyVerticle(final Vertx vertx) {
        webClient = WebClient.create(vertx, new WebClientOptions());
        netClient = vertx.createNetClient(new NetClientOptions());
        httpServer = vertx.createHttpServer();
    }

    @Override
    public void start() {
        Log.info("Starting domain proxy server...");
        httpServer.requestHandler(request -> {
            if (request.method() == HttpMethod.GET) {
                handleGetRequest(request);
            } else if (request.method() == HttpMethod.CONNECT) {
                handleConnectRequest(request);
            }
        });
        httpServer.listen(httpServerPort, result -> {
            if (result.succeeded()) {
                Log.infof("Server is now listening on port %d", httpServerPort);
            } else {
                Log.errorf(result.cause(), "Failed to bind server");
            }
        });
    }

    private void handleGetRequest(final HttpServerRequest request) {
        Log.info("Handling HTTP GET Request");
        if (isTargetWhitelisted(request.authority().host(), request)) {
            webClient.getAbs(request.uri()).send(asyncResult -> {
                if (asyncResult.succeeded()) {
                    final HttpResponse<Buffer> response = asyncResult.result();
                    if (response.statusCode() != HttpResponseStatus.OK.code()) {
                        Log.errorf("Response code: %d, message: %s, body: %s", response.statusCode(), response.statusMessage(),
                                response.bodyAsString());
                    }
                    request.response()
                            .setStatusCode(response.statusCode())
                            .headers().addAll(response.headers());
                    request.response().end(response.body());
                } else {
                    Log.errorf(asyncResult.cause(), "Failed to get response");
                    request.response()
                            .setStatusCode(HttpResponseStatus.BAD_GATEWAY.code())
                            .setStatusMessage(HttpResponseStatus.BAD_GATEWAY.reasonPhrase())
                            .end("The server received an invalid response from the upstream server.");
                }
            });
        }
    }

    private void handleConnectRequest(final HttpServerRequest request) {
        Log.info("Handling HTTPS CONNECT request"); //
        final String targetHost = request.authority().host();
        if (isTargetWhitelisted(targetHost, request)) {
            int targetPort = request.authority().port();
            if (targetPort == -1) {
                targetPort = HTTPS_PORT;
            }
            netClient.connect(targetPort, targetHost, targetConnect -> {
                if (targetConnect.succeeded()) {
                    final NetSocket targetSocket = targetConnect.result();
                    request.toNetSocket().onComplete(sourceConnect -> {
                        if (sourceConnect.succeeded()) {
                            final NetSocket sourceSocket = sourceConnect.result();
                            sourceSocket.handler(targetSocket::write);
                            targetSocket.handler(sourceSocket::write);
                            sourceSocket.closeHandler(v -> targetSocket.close());
                            targetSocket.closeHandler(v -> sourceSocket.close());
                        } else {
                            Log.errorf(sourceConnect.cause(), "Failed to connect to source");
                        }
                    });
                } else {
                    Log.errorf(targetConnect.cause(), "Failed to connect to target");
                    request.response()
                            .setStatusCode(HttpResponseStatus.BAD_GATEWAY.code())
                            .setStatusMessage(HttpResponseStatus.BAD_GATEWAY.reasonPhrase())
                            .end("The server received an invalid response from the upstream server.");
                }
            });
        }
    }

    private boolean isTargetWhitelisted(final String targetHost, final HttpServerRequest request) {
        Log.infof("Target %s", targetHost);
        if (!proxyTargetWhitelist.contains(targetHost)) {
            Log.error("Target is not in whitelist");
            request.response()
                    .setStatusCode(HttpResponseStatus.NOT_FOUND.code())
                    .setStatusMessage(HttpResponseStatus.NOT_FOUND.reasonPhrase())
                    .end("The requested resource was not found.");
            return false;
        }
        return true;
    }
}

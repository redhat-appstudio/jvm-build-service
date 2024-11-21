package com.redhat.hacbs.domainproxy.server;

import static com.redhat.hacbs.domainproxy.common.CommonIOUtil.TIMEOUT_MS;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
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

    @Inject
    @ConfigProperty(name = "quarkus.rest-client.non-proxy-hosts")
    Set<String> nonProxyHosts;

    private final WebClient webClient;
    private final NetClient netClient;
    private final HttpServer httpServer;

    private AtomicInteger counter = new AtomicInteger(0);

    public ExternalProxyVerticle(final Vertx vertx) {
        webClient = WebClient.create(vertx, new WebClientOptions()
                .setMaxPoolSize(50)
                .setKeepAlive(true)
                .setIdleTimeout(60)
                .setConnectTimeout(TIMEOUT_MS));
        netClient = vertx.createNetClient(new NetClientOptions()
                .setReuseAddress(true)
                .setIdleTimeout(60)
                .setConnectTimeout(TIMEOUT_MS));
        httpServer = vertx.createHttpServer();
    }

    @Override
    public void start() {
        Log.info("Starting domain proxy server...");
        Log.infof("Proxy target whitelist: %s", proxyTargetWhitelist); // TODO remove
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
                Quarkus.asyncExit();
            }
        });
    }

    private void handleGetRequest(final HttpServerRequest request) {
        Log.info("Handling HTTP GET Request");
        final int requestNo = counter.incrementAndGet();
        Log.infof("Request no: %d", requestNo);
        if (isTargetWhitelisted(request.authority().host(), request)) {
            Log.infof("Target URI %s", request.uri());
            final long startTime = System.currentTimeMillis();
            vertx.executeBlocking(promise -> {
                webClient.getAbs(request.uri()).send(asyncResult -> {
                    Log.infof("Request %d took %d ms", requestNo, System.currentTimeMillis() - startTime);
                    if (asyncResult.succeeded()) {
                        final HttpResponse<Buffer> response = asyncResult.result();
                        if (response.statusCode() != HttpResponseStatus.OK.code()) {
                            Log.errorf("Response code: %d, message: %s, body: %s", response.statusCode(),
                                    response.statusMessage(),
                                response.bodyAsString());
                        }
                        request.response()
                            .setStatusCode(response.statusCode())
                            .headers().addAll(response.headers());
                        request.response().end(response.body());
                    } else {
                        handleErrorResponse(request, asyncResult.cause(), "Failed to get response");
                    }
                });
            }, res -> {
                if (res.failed()) {
                    Log.errorf(res.cause(), "Failed to process GET request asynchronously");
                }
            });
        }
    }

    private void handleConnectRequest(final HttpServerRequest request) {
        Log.info("Handling HTTPS CONNECT request");
        final int requestNo = counter.incrementAndGet();
        Log.infof("Request no: %d", requestNo);
        final String targetHost = request.authority().host();
        if (isTargetWhitelisted(targetHost, request)) {
            Log.infof("Target URI %s", request.uri());
            final long startTime = System.currentTimeMillis();
            vertx.executeBlocking(promise -> {
                int targetPort = request.authority().port();
                if (targetPort == -1) {
                    targetPort = HTTPS_PORT;
                }
                netClient.connect(targetPort, targetHost, targetConnect -> {
                    Log.infof("Request %d took %d ms", requestNo, System.currentTimeMillis() - startTime);
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
                                handleErrorResponse(request, sourceConnect.cause(), "Failed to connect to source");
                            }
                        });
                    } else {
                        handleErrorResponse(request, targetConnect.cause(), "Failed to connect to target");
                    }
                });
            }, res -> {
                if (res.failed()) {
                    Log.errorf(res.cause(), "Failed to process CONNECT request asynchronously");
                }
            });
        }
    }

    private boolean isTargetWhitelisted(final String targetHost, final HttpServerRequest request) {
        Log.infof("Target host %s", targetHost);
        if (!proxyTargetWhitelist.contains(targetHost) && !nonProxyHosts.contains(targetHost)) {
            Log.error("Target host is not whitelisted or a non-proxy host");
            request.response()
                    .setStatusCode(HttpResponseStatus.NOT_FOUND.code())
                    .setStatusMessage(HttpResponseStatus.NOT_FOUND.reasonPhrase())
                    .end("The requested resource was not found.");
            return false;
        }
        return true;
    }

    private void handleErrorResponse(final HttpServerRequest request, final Throwable cause, final String message) {
        Log.errorf(cause, message);
        request.response()
                .setStatusCode(HttpResponseStatus.BAD_GATEWAY.code())
                .setStatusMessage(HttpResponseStatus.BAD_GATEWAY.reasonPhrase())
                .end(message + ": " + cause.getMessage());
    }

    @Override
    public void stop() {
        Log.info("Shutting down domain proxy server...");
        webClient.close();
        netClient.close();
        httpServer.close(ar -> {
            if (ar.succeeded()) {
                Log.info("Server shut down successfully.");
            } else {
                Log.errorf(ar.cause(), "Server shutdown failed");
            }
        });
    }
}

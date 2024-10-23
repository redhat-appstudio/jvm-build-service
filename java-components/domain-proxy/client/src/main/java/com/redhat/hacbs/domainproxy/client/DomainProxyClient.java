package com.redhat.hacbs.domainproxy.client;

import static com.redhat.hacbs.domainproxy.common.CommonIOUtil.createChannelToSocketWriter;
import static com.redhat.hacbs.domainproxy.common.CommonIOUtil.createSocketToChannelWriter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;

@Startup
@Singleton
public class DomainProxyClient {

    @Inject
    @ConfigProperty(name = "client-domain-socket")
    String domainSocket;

    @Inject
    @ConfigProperty(name = "client-http-port")
    int clientHttpPort;

    @Inject
    @ConfigProperty(name = "byte-buffer-size")
    int byteBufferSize;

    @PostConstruct
    public void start() {
        Log.info("Starting domain proxy client...");
        new Thread(() -> {
            try (final ServerSocket serverSocket = new ServerSocket(clientHttpPort)) {
                while (true) {
                    final Socket socket = serverSocket.accept();
                    final UnixDomainSocketAddress address = UnixDomainSocketAddress.of(domainSocket);
                    final SocketChannel channel = SocketChannel.open(address);
                    // Write from socket to channel
                    Thread.startVirtualThread(createSocketToChannelWriter(byteBufferSize, socket, channel));
                    // Write from channel to socket
                    Thread.startVirtualThread(createChannelToSocketWriter(byteBufferSize, channel, socket));
                }
            } catch (final IOException e) {
                Log.errorf(e, "Error initialising domain proxy client");
            }
            Quarkus.asyncExit();
        }).start();
    }
}

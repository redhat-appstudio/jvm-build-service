package com.redhat.hacbs.domainproxy;

import java.io.IOException;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.domainproxy.common.CommonIOUtil;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;

@Startup
@Singleton
public class DomainProxyServer {

    static final String HOST = "localhost";

    @Inject
    @ConfigProperty(name = "server-domain-socket")
    String domainSocket;

    @Inject
    @ConfigProperty(name = "server-http-port")
    int serverHttpPort;

    @Inject
    @ConfigProperty(name = "byte-buffer-size")
    int byteBufferSize;

    @PostConstruct
    public void start() {
        Log.info("Starting domain proxy server...");
        new Thread(() -> {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.delete(Path.of(domainSocket));
                } catch (final IOException e) {
                    Log.errorf(e, "Error deleting domain socket");
                }
            }));
            try (final ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                final UnixDomainSocketAddress address = UnixDomainSocketAddress.of(domainSocket);
                serverChannel.bind(address);
                while (true) {
                    final SocketChannel channel = serverChannel.accept();
                    final Socket socket = new Socket(HOST, serverHttpPort);
                    // write from socket to channel
                    CommonIOUtil.createSocketToChannelWriter(byteBufferSize, socket, channel).start();
                    // write from channel to socket
                    CommonIOUtil.createChannelToSocketWriter(byteBufferSize, channel, socket).start();
                }
            } catch (final IOException e) {
                Log.errorf(e, "Error initialising domain proxy server");
            }
            Quarkus.asyncExit();
        }).start();
    }
}

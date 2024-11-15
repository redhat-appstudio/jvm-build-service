package com.redhat.hacbs.domainproxy.server;

import static com.redhat.hacbs.domainproxy.common.CommonIOUtil.LOCALHOST;
import static com.redhat.hacbs.domainproxy.common.CommonIOUtil.TIMEOUT_MS;
import static com.redhat.hacbs.domainproxy.common.CommonIOUtil.channelToChannelBiDirectionalHandler;
import static java.lang.Thread.currentThread;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;

@Startup
@Singleton
public class DomainProxyServer {

    @Inject
    @ConfigProperty(name = "server-domain-socket")
    String domainSocket;

    @Inject
    @ConfigProperty(name = "server-http-port")
    int httpServerPort;

    @Inject
    @ConfigProperty(name = "byte-buffer-size")
    int byteBufferSize;

    private ExecutorService executor;

    @PostConstruct
    public void start() {
        Log.infof("Byte buffer size %d", byteBufferSize); // TODO Remove
        executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(this::startServer);
    }

    private void startServer() {
        try (final ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final Selector selector = Selector.open()) {
            currentThread().setName("connectionHandler");
            serverChannel.bind(UnixDomainSocketAddress.of(domainSocket));
            serverChannel.configureBlocking(false);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            while (!currentThread().isInterrupted()) {
                if (selector.select(TIMEOUT_MS) > 0) {
                    final Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        final SelectionKey key = keys.next();
                        keys.remove();
                        if (key.isAcceptable()) {
                            final ServerSocketChannel keyChannel = (ServerSocketChannel) key.channel();
                            final SocketChannel domainSocketChannel = keyChannel.accept();
                            final SocketChannel httpServerChannel = SocketChannel
                                    .open(new InetSocketAddress(LOCALHOST, httpServerPort));
                            executor.submit(channelToChannelBiDirectionalHandler(byteBufferSize, httpServerChannel,
                                    domainSocketChannel));
                        }
                    }
                }
            }
        } catch (final IOException e) {
            Log.errorf(e, "Error initialising domain proxy server");
        }
        Quarkus.asyncExit();
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
        try {
            Files.deleteIfExists(Path.of(domainSocket));
        } catch (final IOException e) {
            Log.errorf(e, "Error deleting domain socket");
        }
    }
}

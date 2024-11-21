package com.redhat.hacbs.domainproxy.client;

import static com.redhat.hacbs.domainproxy.common.CommonIOUtil.LOCALHOST;
import static com.redhat.hacbs.domainproxy.common.CommonIOUtil.TIMEOUT_MS;
import static com.redhat.hacbs.domainproxy.common.CommonIOUtil.channelToChannelBiDirectionalHandler;
import static java.lang.Thread.currentThread;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.redhat.hacbs.domainproxy.common.CommonIOUtil;
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

    private ExecutorService executor;

    @PostConstruct
    public void start() {
        Log.info("Starting domain proxy client...");
        Log.infof("Byte buffer size %d", byteBufferSize); // TODO Remove
        executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(this::startClient);
    }

    private void startClient() {
        try (final ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.INET);
                final Selector selector = Selector.open()) {
            currentThread().setName("connectionHandler");
            final InetSocketAddress address = new InetSocketAddress(LOCALHOST, clientHttpPort);
            serverChannel.bind(address);
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
                            final SocketChannel httpClientChannel = keyChannel.accept();
                            final SocketChannel domainSocketChannel = SocketChannel
                                    .open(UnixDomainSocketAddress.of(domainSocket));
                            executor.submit(channelToChannelBiDirectionalHandler(byteBufferSize, httpClientChannel,
                                    domainSocketChannel));
                            Log.info("Before thread dump");
                            CommonIOUtil.threadDump();
                            Log.info("After thread dump");
                        }
                    }
                }
            }
        } catch (final IOException e) {
            Log.errorf(e, "Error initialising domain proxy client");
        }
        Quarkus.asyncExit();
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}

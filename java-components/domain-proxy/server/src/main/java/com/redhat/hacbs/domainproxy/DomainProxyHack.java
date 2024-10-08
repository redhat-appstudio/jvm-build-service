package com.redhat.hacbs.domainproxy;

import java.io.IOException;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;

@Startup
@Singleton
public class DomainProxyHack {

    @Inject
    @ConfigProperty(name = "server-domain-socket")
    String domainSocket;

    @PostConstruct
    public void start() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Files.delete(Path.of(domainSocket));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }));
                try {
                    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(domainSocket);
                    var socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                    socket.bind(address);
                    while (true) {
                        SocketChannel channel = socket.accept();
                        Socket s = new Socket("localhost", 2000);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                int r;
                                byte[] buf = new byte[1024];
                                try {
                                    while ((r = s.getInputStream().read(buf)) > 0) {
                                        channel.write(ByteBuffer.wrap(buf, 0, r));
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                } finally {
                                    try {
                                        channel.close();
                                    } catch (Exception ex) {
                                        ex.printStackTrace();
                                    }
                                    try {
                                        s.close();
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                }
                            }
                        }).start();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                ByteBuffer buf = ByteBuffer.allocate(1024);
                                buf.clear();
                                try {
                                    while (channel.read(buf) > 0) {
                                        buf.flip();
                                        s.getOutputStream().write(buf.array(), buf.arrayOffset(), buf.remaining());
                                        buf.clear();
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                } finally {

                                    try {
                                        channel.close();
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                    try {
                                        s.close();
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                }
                            }
                        }).start();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Quarkus.asyncExit();
            }
        }).start();
    }
}

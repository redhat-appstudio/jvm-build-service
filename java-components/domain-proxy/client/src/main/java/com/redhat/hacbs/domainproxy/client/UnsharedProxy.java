package com.redhat.hacbs.domainproxy.client;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import jakarta.annotation.PostConstruct;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;

@Startup
public class UnsharedProxy {

    @PostConstruct
    public void start() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try (ServerSocket socket = new ServerSocket(8080)) {
                    while (true) {
                        Socket s = socket.accept();
                        UnixDomainSocketAddress address = UnixDomainSocketAddress.of("/tmp/domainserver");
                        SocketChannel channel = SocketChannel.open(address);
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

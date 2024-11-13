package com.redhat.hacbs.domainproxy.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

import org.jboss.logging.Logger;

public final class CommonIOUtil {

    private static final Logger LOG = Logger.getLogger(CommonIOUtil.class);

    public static final String LOCALHOST = "localhost";
    public static final int SELECTOR_TIMEOUT_MS = 1000;

    private CommonIOUtil() {
    }

    public static Runnable createChannelToChannelBiDirectionalHandler(final int byteBufferSize,
            final SocketChannel leftChannel,
            final SocketChannel rightChannel) {
        return () -> {
            Thread.currentThread().setName("ChannelToChannelBiDirectionalHandler");
            int bytesRead = 0, bytesWritten = 0;
            final ByteBuffer buffer = ByteBuffer.allocate(byteBufferSize);
            try (final Selector selector = Selector.open()) {
                leftChannel.configureBlocking(false);
                rightChannel.configureBlocking(false);
                leftChannel.register(selector, SelectionKey.OP_READ);
                rightChannel.register(selector, SelectionKey.OP_WRITE);
                while (leftChannel.isOpen() && rightChannel.isOpen()) {
                    if (selector.select(SELECTOR_TIMEOUT_MS) > 0) {
                        final Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                        while (keys.hasNext()) {
                            final SelectionKey key = keys.next();
                            keys.remove();
                            if (key.isReadable()) {
                                if (key.channel() == leftChannel) {
                                    final int bytes = transferData(leftChannel, rightChannel, buffer);
                                    if (bytes == 0) {
                                        return;
                                    }
                                    bytesRead += bytes;
                                } else if (key.channel() == rightChannel) {
                                    final int bytes = transferData(rightChannel, leftChannel, buffer);
                                    if (bytes == 0) {
                                        return;
                                    }
                                    bytesRead += bytes;
                                }
                            }
                            if (key.isWritable()) {
                                if (key.channel() == leftChannel) {
                                    bytesWritten += transferData(leftChannel, rightChannel, buffer);
                                } else if (key.channel() == rightChannel) {
                                    bytesWritten += transferData(rightChannel, leftChannel, buffer);
                                }
                            }
                        }
                    }
                }
            } catch (final IOException e) {
                LOG.errorf(e, "Error in bi-directional channel handling");
            } finally {
                closeSocketChannel(leftChannel, rightChannel);
                LOG.infof("Read %d bytes between channels", bytesRead);
                LOG.infof("Wrote %d bytes between channels", bytesWritten);
            }
        };
    }

    private static int transferData(final SocketChannel sourceChannel, final SocketChannel destinationChannel,
            final ByteBuffer buffer)
            throws IOException {
        buffer.clear();
        final int bytesRead = sourceChannel.read(buffer);
        if (bytesRead == -1) {
            return 0;
        }
        buffer.flip();
        while (buffer.hasRemaining()) {
            destinationChannel.write(buffer);
        }
        return bytesRead;
    }

    private static void closeSocketChannel(final SocketChannel sourceChannel, final SocketChannel destinationChannel) {
        try {
            if (sourceChannel != null && sourceChannel.isOpen()) {
                sourceChannel.close();
            }
        } catch (final IOException e) {
            LOG.errorf(e, "Error closing source channel");
        }
        try {
            if (destinationChannel != null && destinationChannel.isOpen()) {
                destinationChannel.close();
            }
        } catch (final IOException e) {
            LOG.errorf(e, "Error closing destination channel");
        }
    }
}

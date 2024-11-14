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
    public static final int TIMEOUT_MS = 1000;

    private CommonIOUtil() {
    }

    private enum Operation {
        READ("Read"),
        WRITE("Wrote");

        final String descriptor;

        Operation(final String descriptor) {
            this.descriptor = descriptor;
        }
    }

    public static Runnable createChannelToChannelBiDirectionalHandler(final int byteBufferSize,
            final SocketChannel leftChannel,
            final SocketChannel rightChannel) {
        return () -> {
            Thread.currentThread().setName("ChannelToChannelBiDirectionalHandler");
            LOG.info("Connections opened");
            int bytesReadLeft = 0;
            int bytesReadRight = 0;
            int bytesWrittenLeft = 0;
            int bytesWrittenRight = 0;
            final ByteBuffer buffer = ByteBuffer.allocate(byteBufferSize);
            try (final Selector selector = Selector.open()) {
                leftChannel.configureBlocking(false);
                rightChannel.configureBlocking(false);
                leftChannel.register(selector, SelectionKey.OP_READ);
                rightChannel.register(selector, SelectionKey.OP_WRITE);
                long bytesTransferredTime = System.currentTimeMillis();
                while (leftChannel.isOpen() && rightChannel.isOpen()) {
                    if (selector.selectNow() > 0) {
                        final Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                        while (keys.hasNext()) {
                            final SelectionKey key = keys.next();
                            keys.remove();
                            int bytesTransferred = 0;
                            if (key.isReadable()) {
                                final Operation operation = Operation.READ;
                                if (key.channel() == leftChannel) {
                                    bytesTransferred = transferData(leftChannel, rightChannel, buffer, operation);
                                    bytesReadLeft += bytesTransferred;
                                } else if (key.channel() == rightChannel) {
                                    bytesTransferred = transferData(rightChannel, leftChannel, buffer, operation);
                                    bytesReadRight += bytesTransferred;
                                }
                            }
                            if (key.isWritable()) {
                                final Operation operation = Operation.WRITE;
                                if (key.channel() == leftChannel) {
                                    bytesTransferred = transferData(leftChannel, rightChannel, buffer, operation);
                                    bytesWrittenLeft += bytesTransferred;
                                } else if (key.channel() == rightChannel) {
                                    bytesTransferred = transferData(rightChannel, leftChannel, buffer, operation);
                                    bytesWrittenRight += bytesTransferred;
                                }
                            }
                            if (bytesTransferred > 0) {
                                bytesTransferredTime = System.currentTimeMillis();
                            }
                        }
                    }
                    if (System.currentTimeMillis() - bytesTransferredTime > TIMEOUT_MS) {
                        break;
                    }
                }
            } catch (final IOException e) {
                LOG.errorf(e, "Error in bi-directional channel handling");
            } finally {
                closeConnections(leftChannel, rightChannel);
                try {
                    final String leftChannelName = leftChannel.getRemoteAddress().getClass().getSimpleName();
                    final String rightChannelName = rightChannel.getRemoteAddress().getClass().getSimpleName();
                    LOG.infof("Read %d total bytes from % channel to %s channel", leftChannelName, rightChannelName, bytesReadLeft);
                    LOG.infof("Read %d total bytes from % channel to %s channel", rightChannelName, leftChannelName, bytesReadRight);
                    LOG.infof("Wrote %d total bytes from % channel to %s channel", leftChannelName, rightChannelName, bytesWrittenLeft);
                    LOG.infof("Wrote %d total bytes from % channel to %s channel", rightChannelName, leftChannelName, bytesWrittenRight);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                LOG.infof("Read %d total bytes between channels overall", bytesReadLeft + bytesReadRight);
                LOG.infof("Wrote %d total bytes between channels overall", bytesWrittenLeft + bytesWrittenRight);
            }
        };
    }

    private static int transferData(final SocketChannel sourceChannel, final SocketChannel destinationChannel,
            final ByteBuffer buffer, final Operation operation)
            throws IOException {
        buffer.clear();
        final int bytesTransferred = sourceChannel.read(buffer);
        if (bytesTransferred <= 0) {
            return 0;
        }
        buffer.flip();
        while (buffer.hasRemaining()) {
            destinationChannel.write(buffer);
        }
        logBytes(sourceChannel, destinationChannel, operation, bytesTransferred);
        return bytesTransferred;
    }

    private static void closeConnections(final SocketChannel sourceChannel, final SocketChannel destinationChannel) {
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
        LOG.info("Connections closed");
    }

    private static void logBytes(final SocketChannel sourceChannel, final SocketChannel destinationChannel,
            final Operation operation,
            final int bytesTransferred) throws IOException {
        if (bytesTransferred > 0) {
            LOG.infof("%s %d bytes from %s channel to %s channel", operation.descriptor, bytesTransferred,
                    sourceChannel.getRemoteAddress().getClass().getSimpleName(),
                    destinationChannel.getRemoteAddress().getClass().getSimpleName());
        }
    }
}

package com.redhat.hacbs.domainproxy.common;

import static java.lang.Thread.currentThread;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
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

    public static Runnable channelToChannelBiDirectionalHandler(final int byteBufferSize,
            final SocketChannel leftChannel,
            final SocketChannel rightChannel) {
        return () -> {
            currentThread().setName("channelToChannelHandler");
            LOG.info("Connections opened");
            final String leftChannelName = getChannelName(leftChannel);
            final String rightChannelName = getChannelName(rightChannel);
            int bytesReadLeft = 0;
            int bytesReadRight = 0;
            int bytesWrittenLeft = 0;
            int bytesWrittenRight = 0;
            final ByteBuffer buffer = ByteBuffer.allocate(byteBufferSize);
            try (final Selector selector = Selector.open()) {
                leftChannel.configureBlocking(false);
                rightChannel.configureBlocking(false);
                leftChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                rightChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
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
                    //                    if (System.currentTimeMillis() - bytesTransferredTime > TIMEOUT_MS) {
                    //                        break;
                    //                    }
                }
            } catch (final IOException e) {
                LOG.errorf(e, "Error in bi-directional channel handling");
            } finally {
                closeConnections(leftChannel, rightChannel);
                LOG.infof("Read %d total bytes from %s channel to %s channel", bytesReadLeft, leftChannelName,
                        rightChannelName);
                LOG.infof("Read %d total bytes from %s channel to %s channel", bytesReadRight, rightChannelName,
                        leftChannelName);
                LOG.infof("Wrote %d total bytes from %s channel to %s channel", bytesWrittenLeft, leftChannelName,
                        rightChannelName);
                LOG.infof("Wrote %d total bytes from %s channel to %s channel", bytesWrittenRight, rightChannelName,
                        leftChannelName);
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

    private static void closeConnections(final SocketChannel leftChannel, final SocketChannel rightChannel) {
        if (leftChannel != null) {
            closeConnection(leftChannel);
        }
        if (rightChannel != null) {
            closeConnection(rightChannel);
        }
        LOG.info("Connections closed");
    }

    private static void closeConnection(final SocketChannel channel) {
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (final IOException e) {
            LOG.errorf(e, "Error closing %s channel", getChannelName(channel));
        }
    }

    private static void logBytes(final SocketChannel sourceChannel, final SocketChannel destinationChannel,
            final Operation operation,
            final int bytesTransferred) {
        if (bytesTransferred > 0) {
            LOG.infof("%s %d bytes from %s channel to %s channel", operation.descriptor, bytesTransferred,
                    getChannelName(sourceChannel),
                    getChannelName(destinationChannel));
        }
    }

    private static String getChannelName(final SocketChannel channel) {
        String channelName = "unknown";
        try {
            channelName = channel.getRemoteAddress().getClass().getSimpleName();
        } catch (final IOException e) {
            LOG.errorf(e, "Error getting channel name");
        }
        return channelName;
    }

    public static void threadDump() throws IOException {
        // Create a timestamp with milliseconds for the file name
        //String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        //String fileName = "/app/thread_dump_" + timestamp + ".txt";
        String threadDumpStr = "";

        // Create a PrintWriter to write the thread dump to a file
        //try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
        // Get the ThreadMXBean instance
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        // Get all thread IDs
        long[] threadIds = threadMXBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadIds, Integer.MAX_VALUE);

        // Write the thread information to the file
        for (ThreadInfo threadInfo : threadInfos) {
            threadDumpStr += "Thread ID: " + threadInfo.getThreadId() + " Name: " + threadInfo.getThreadName() + "\n";
            threadDumpStr += "Thread State: " + threadInfo.getThreadState() + "\n";
            StackTraceElement[] stackTrace = threadInfo.getStackTrace();
            for (StackTraceElement stackTraceElement : stackTrace) {
                threadDumpStr += "\t" + stackTraceElement + "\n";
            }
        }
        //}

        LOG.infof(threadDumpStr);
    }
}

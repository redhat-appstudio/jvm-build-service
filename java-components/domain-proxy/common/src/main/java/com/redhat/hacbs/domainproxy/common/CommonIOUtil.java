package com.redhat.hacbs.domainproxy.common;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;

import org.jboss.logging.Logger;

public final class CommonIOUtil {

    private static final Logger LOG = Logger.getLogger(CommonIOUtil.class);

    public static Runnable createSocketToChannelWriter(final int byteBufferSize, final Socket socket,
            final SocketChannel channel) {
        // Write from socket to channel
        return () -> {
            Thread.currentThread().setName("SocketToChannelWriter");
            int r;
            final byte[] buf = new byte[byteBufferSize];
            int bytesWritten = 0;
            LOG.info("Writing from socket to channel");
            try {
                while ((r = socket.getInputStream().read(buf)) > 0) {
                    LOG.infof("Read %d bytes from socket", r);
                    channel.write(ByteBuffer.wrap(buf, 0, r));
                    LOG.infof("Wrote %d bytes to channel", r);
                    bytesWritten += r;
                }
            } catch (final ClosedChannelException ignore) {
                LOG.info("Channel closed");
            } catch (final SocketException ignore) {
                LOG.info("Socket closed");
            } catch (final SocketTimeoutException ignore) {
                LOG.info("Socket timed out");
            } catch (final IOException e) {
                LOG.errorf(e, "Error writing from socket to channel");
            } finally {
                try {
                    channel.close();
                } catch (final IOException e) {
                    LOG.errorf(e, "Error closing channel");
                }
                try {
                    socket.close();
                } catch (final IOException e) {
                    LOG.errorf(e, "Error closing socket");
                }
            }
            LOG.infof("Wrote %d bytes from socket to channel", bytesWritten);
        };
    }

    public static Runnable createChannelToSocketWriter(final int byteBufferSize, final SocketChannel channel,
            final Socket socket) {
        // Write from channel to socket
        return () -> {
            Thread.currentThread().setName("ChannelToSocketWriter");
            int r;
            final ByteBuffer buf = ByteBuffer.allocate(byteBufferSize);
            buf.clear();
            int bytesWritten = 0;
            LOG.info("Writing from channel to socket");
            try {
                while ((r = channel.read(buf)) > 0) {
                    LOG.infof("Read %d bytes from channel", r);
                    buf.flip();
                    socket.getOutputStream().write(buf.array(), buf.arrayOffset(), buf.remaining());
                    LOG.infof("Wrote %d bytes to socket", r);
                    buf.clear();
                    bytesWritten += r;
                }
            } catch (final ClosedChannelException ignore) {
                LOG.info("Channel closed");
            } catch (final SocketException ignore) {
                LOG.info("Socket closed");
            } catch (final SocketTimeoutException ignore) {
                LOG.info("Socket timed out");
            } catch (final IOException e) {
                LOG.errorf(e, "Error writing from channel to socket");
            } finally {
                try {
                    channel.close();
                } catch (final IOException e) {
                    LOG.errorf(e, "Error closing channel");
                }
                try {
                    socket.close();
                } catch (final IOException e) {
                    LOG.errorf(e, "Error closing socket");
                }
            }
            LOG.infof("Wrote %d bytes from channel to socket", bytesWritten);
        };
    }
}

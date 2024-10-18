package com.redhat.hacbs.domainproxy.common;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.SocketChannel;

import org.jboss.logging.Logger;

public final class CommonIOUtil {

    private static final Logger LOG = Logger.getLogger(CommonIOUtil.class);

    public static Thread createSocketToChannelWriter(final int byteBufferSize, final Socket socket,
            final SocketChannel channel) {
        // write from socket to channel
        return new Thread(() -> {
            int r;
            final byte[] buf = new byte[byteBufferSize];
            int bytesWritten = 0;
            LOG.info("Writing from socket to channel");
            try {
                while ((r = socket.getInputStream().read(buf)) > 0) {
                    channel.write(ByteBuffer.wrap(buf, 0, r));
                    bytesWritten += r;
                }
            } catch (final SocketException ignore) {
                LOG.info("Socket closed");
            } catch (final IOException e) {
                LOG.errorf(e, "Error writing from socket to channel");
            } finally {
                try {
                    channel.close();
                } catch (final Exception e) {
                    LOG.errorf(e, "Error closing channel");
                }
                try {
                    socket.close();
                } catch (final IOException e) {
                    LOG.errorf(e, "Error closing socket");
                }
            }
            LOG.infof("Wrote %d bytes from socket to channel", bytesWritten);
        });
    }

    public static Thread createChannelToSocketWriter(final int byteBufferSize, final SocketChannel channel,
            final Socket socket) {
        // write from channel to socket
        return new Thread(() -> {
            int r;
            final ByteBuffer buf = ByteBuffer.allocate(byteBufferSize);
            buf.clear();
            int bytesWritten = 0;
            LOG.info("Writing from channel to socket");
            try {
                while ((r = channel.read(buf)) > 0) {
                    buf.flip();
                    socket.getOutputStream().write(buf.array(), buf.arrayOffset(), buf.remaining());
                    buf.clear();
                    bytesWritten += r;
                }
            } catch (final AsynchronousCloseException ignore) {
                LOG.info("Channel closed");
            } catch (final Exception e) {
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
        });
    }
}

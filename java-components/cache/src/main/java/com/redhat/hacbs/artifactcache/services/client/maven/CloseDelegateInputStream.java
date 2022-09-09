package com.redhat.hacbs.artifactcache.services.client.maven;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

public class CloseDelegateInputStream extends InputStream {

    final InputStream delegate;
    final Closeable closeable;

    public CloseDelegateInputStream(InputStream delegate, Closeable closeable) {
        this.delegate = delegate;
        this.closeable = closeable;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return delegate.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        try {
            delegate.close();
        } finally {
            closeable.close();
        }
    }

    @Override
    public int read() throws IOException {
        return delegate.read();
    }
}

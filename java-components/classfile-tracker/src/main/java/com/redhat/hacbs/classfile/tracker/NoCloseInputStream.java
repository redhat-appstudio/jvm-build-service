package com.redhat.hacbs.classfile.tracker;

import java.io.IOException;
import java.io.InputStream;

public class NoCloseInputStream extends InputStream {

    final InputStream delegate;

    public NoCloseInputStream(InputStream delegate) {
        this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
        return delegate.read();
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
        //ignore
    }
}
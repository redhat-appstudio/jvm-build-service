package com.redhat.hacbs.sidecar.resources;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

class HashingOutputStream extends OutputStream {

    final OutputStream delegate;
    final MessageDigest md;
    String hash;

    boolean closed;

    HashingOutputStream(OutputStream delegate) {
        this.delegate = delegate;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] { (byte) b });
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
        md.update(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
        if (!closed) {
            closed = true;
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(40);
            for (int i = 0; i < digest.length; ++i) {
                sb.append(Integer.toHexString((digest[i] & 0xFF) | 0x100).substring(1, 3));
            }
            hash = sb.toString();
        }
    }
}

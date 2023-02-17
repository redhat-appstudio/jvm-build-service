package com.redhat.hacbs.resources.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashUtil {

    private HashUtil() {
    }

    public static String sha1(String value) {
        return sha1(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha1(byte[] value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(value);
            StringBuilder sb = new StringBuilder(40);
            for (int i = 0; i < digest.length; ++i) {
                sb.append(Integer.toHexString((digest[i] & 0xFF) | 0x100).substring(1, 3));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha1(InputStream value) {
        return hashStream(value, "SHA-1");
    }

    public static String md5(InputStream value) {
        return hashStream(value, "MD5");
    }

    public static String hashStream(InputStream value, String algo) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] buf = new byte[1024];
            int r;
            while ((r = value.read(buf)) > 0) {
                md.update(buf, 0, r);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(40);
            for (int i = 0; i < digest.length; ++i) {
                sb.append(Integer.toHexString((digest[i] & 0xFF) | 0x100).substring(1, 3));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                value.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}

package com.redhat.hacbs.artifactcache.services.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ShaUtil {

    private static final String SHA_256 = "SHA-256";
    private static final String GAV_FORMAT = "%s:%s:%s";

    private ShaUtil() {
    }

    public static String sha256sum(String group, String artifact, String version) {
        String gav = String.format(GAV_FORMAT, group, artifact, version);
        return sha256sum(gav);
    }

    public static String sha256sum(String gav) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] hashedGav = digest.digest(gav.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder(2 * hashedGav.length);
            for (int i = 0; i < hashedGav.length; i++) {
                String hex = Integer.toHexString(0xff & hashedGav[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }
    }
}

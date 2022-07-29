package com.redhat.hacbs.sidecar.resources.deploy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DeployerUtil {

    private static final String SHA_256 = "SHA-256";
    private static final String GAV_FORMAT = "%s:%s:%s";
    private static final Pattern ARTIFACT_PATH = Pattern.compile(".*/([^/]+)/([^/]+)/([^/]+)");

    private DeployerUtil() {
    }

    public static boolean shouldIgnore(Set<String> doNotDeploy, String name) {
        Matcher m = ARTIFACT_PATH.matcher(name);
        if (!m.matches()) {
            return false;
        }
        return doNotDeploy.contains(m.group(1));
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

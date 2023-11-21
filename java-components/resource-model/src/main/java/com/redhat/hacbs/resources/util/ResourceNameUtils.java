package com.redhat.hacbs.resources.util;

public class ResourceNameUtils {

    public static String nameFromGav(String gav) {
        String hash = HashUtil.sha1(gav).substring(0, 8);
        StringBuilder newName = new StringBuilder();
        boolean lastDot = false;
        String namePart = gav.substring(gav.indexOf(':') + 1);
        for (var i : namePart.toCharArray()) {
            if (Character.isAlphabetic(i) || Character.isDigit(i)) {
                newName.append(Character.toLowerCase(i));
                lastDot = false;
            } else {
                if (!lastDot) {
                    newName.append('.');
                }
                lastDot = true;
            }
        }
        newName.append("-");
        newName.append(hash);
        return newName.toString();
    }

    public static String dependencyBuildName(String url, String tag, String path) {
        return HashUtil.md5(url + tag + (path == null ? "" : path));
    }
}
